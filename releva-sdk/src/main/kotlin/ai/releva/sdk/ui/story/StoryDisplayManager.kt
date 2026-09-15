package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.story.StoryDisplayController
import ai.releva.sdk.types.response.StoryResponse
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Collects from [StoryDisplayController.storyFlow] and launches [StoryViewerActivity].
 * Call [attach] from your Activity's onCreate().
 *
 * Both [client] and [onLinkTap] must be set before calling [attach].
 *
 * Stories are shown one at a time. The trigger path emits every eligible story in one
 * synchronous pass, so without a queue each emission started its own viewer Activity and
 * they stacked: a page with six stories opened six full-screen viewers on top of one
 * another, and closing one only revealed the next. Measured on a device, with one of them
 * set to `loop`, which left the app unreachable behind a story that never ends.
 *
 * [attach] is documented per-Activity, but nothing enforced "one host at a time": two
 * activities in the back stack (A attaches, user navigates to B which also attaches) each
 * ran their own `CREATED` collector against this same object-level queue, so every
 * emission was queued twice and six stories showed twelve viewers. [seenTokens] dedupes
 * across hosts, and [attachedActivities] stops the same activity instance from attaching a
 * second collector if `attach()` is called twice.
 */
object StoryDisplayManager {

    private const val TAG = "StoryDisplayManager"

    private var client: RelevaClient? = null
    private var onLinkTap: ((String) -> Unit)? = null

    private val queue = ArrayDeque<StoryResponse>()

    // Tokens already queued or shown, so a second host's collector (or any other
    // redelivery) does not enqueue the same story again. Cleared together with the queue.
    private val seenTokens = mutableSetOf<String>()

    // The launch key of the viewer currently on screen, or null if none. Identity-based
    // rather than a bare boolean: a boolean has to be cleared from somewhere, and the only
    // "somewhere" that reliably fires (a host's onResume, since back never reaches
    // onClose) fires for *any* reason the host resumes, including another host's viewer
    // still being alive. Deriving liveness from the key itself removes that ordering
    // dependency: onResume clears the key only if that viewer is not (or no longer) alive.
    private var activeViewerKey: String? = null

    // Activities that already hold a live collector, so calling attach() twice for the
    // same instance (e.g. a re-entrant onCreate) does not start a second one.
    private val attachedActivities = java.util.WeakHashMap<FragmentActivity, Boolean>()

    fun setClient(client: RelevaClient) {
        this.client = client
    }

    fun setOnLinkTap(onLinkTap: (String) -> Unit) {
        this.onLinkTap = onLinkTap
    }

    /**
     * Start collecting story events and showing the story viewer.
     * Requires [setClient] and [setOnLinkTap] to be called first.
     */
    fun attach(activity: FragmentActivity) {
        requireNotNull(client) { "StoryDisplayManager: call setClient() before attach()" }
        requireNotNull(onLinkTap) { "StoryDisplayManager: call setOnLinkTap() before attach()" }

        if (attachedActivities.put(activity, true) == true) {
            Log.w(TAG, "attach() called twice for the same activity; ignoring the second call")
            return
        }

        // The viewer covers the host, so the host is resumed again exactly when a story has
        // gone away — by its close button, by back, or by finishing on its end behaviour.
        // Only clear activeViewerKey if it does not refer to a viewer that is still alive:
        // with two hosts in the back stack, host B resuming must not clear host A's viewer
        // out from under it.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val key = activeViewerKey
                if (key == null || !StoryViewerActivity.isAlive(key)) {
                    activeViewerKey = null
                }
                pump(activity)
            }
        })

        // CREATED, not STARTED. The viewer is an Activity that covers the host, so with
        // STARTED the collector is cancelled the instant the first story opens — and
        // storyFlow has no replay, so every story emitted in the same pass as that first
        // one is gone before anything can queue it. A page triggering three stories showed
        // one and silently dropped two: the queue below could only ever sequence what the
        // collector had already received, and it had received exactly one.
        //
        // Collecting while CREATED keeps the collector alive across the host being stopped,
        // so the rest of the pass lands in the queue and pump() shows them as the viewer
        // closes. pump() is safe to call while stopped: activeViewerKey is still set, so it
        // queues and returns rather than launching a second viewer over the first.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                StoryDisplayController.storyFlow.collect { story ->
                    if (seenTokens.add(story.token)) {
                        queue.addLast(story)
                        pump(activity)
                    }
                }
            }
        }
    }

    /** Show the next story if none is on screen. */
    private fun pump(activity: FragmentActivity) {
        if (activeViewerKey != null) return
        // The collector now survives the host being stopped, so pump() can run when the
        // host is finishing (it called finish() itself) or already destroyed (a
        // configuration change while a viewer was up). Launching from a dead host's
        // context would crash or leak.
        if (activity.isFinishing || activity.isDestroyed) return
        val story = queue.removeFirstOrNull() ?: return
        val cl = client ?: return
        Log.d(TAG, "Showing story ${story.token} (${queue.size} queued behind it)")

        // A WeakReference, not the activity itself: onClose is stored on the viewer for as
        // long as the viewer lives, and if the host were captured strongly and destroyed
        // while the viewer is open, the host could not be collected for that whole time.
        val hostRef = WeakReference(activity)
        activeViewerKey = StoryViewerActivity.launch(
            context = activity,
            story = story,
            client = cl,
            onLinkTap = onLinkTap,
            onClose = {
                activeViewerKey = null
                hostRef.get()?.let { host ->
                    if (!host.isFinishing && !host.isDestroyed) pump(host)
                }
            }
        )
    }

    /**
     * Drop anything still waiting. Called when a fresh set of stories arrives so a queue
     * built for a previous screen cannot surface later on an unrelated one.
     *
     * Deliberately does not touch [activeViewerKey]: a viewer already on screen is not
     * "queued" state left over from the old screen, it is what the user is looking at
     * right now, and clearing it here would let pump() launch a second viewer on top of it.
     */
    fun clearQueue() {
        queue.clear()
        seenTokens.clear()
    }
}
