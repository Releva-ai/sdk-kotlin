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
 */
object StoryDisplayManager {

    private const val TAG = "StoryDisplayManager"

    private var client: RelevaClient? = null
    private var onLinkTap: ((String) -> Unit)? = null

    private val queue = ArrayDeque<StoryResponse>()
    private var showing = false

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

        // The viewer covers the host, so the host is resumed again exactly when a story has
        // gone away — by its close button, by back, or by finishing on its end behaviour.
        // Clearing the flag here rather than only from the viewer's onClose covers all
        // three; back in particular never reaches onClose.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                showing = false
                pump(activity)
            }
        })

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StoryDisplayController.storyFlow.collect { story ->
                    queue.addLast(story)
                    pump(activity)
                }
            }
        }
    }

    /** Show the next story if none is on screen. */
    private fun pump(activity: FragmentActivity) {
        if (showing) return
        val story = queue.removeFirstOrNull() ?: return
        val cl = client ?: return
        showing = true
        Log.d(TAG, "Showing story ${story.token} (${queue.size} queued behind it)")
        StoryViewerActivity.launch(
            context = activity,
            story = story,
            client = cl,
            onLinkTap = onLinkTap,
            onClose = {
                showing = false
                pump(activity)
            }
        )
    }

    /**
     * Drop anything still waiting. Called when a fresh set of stories arrives so a queue
     * built for a previous screen cannot surface later on an unrelated one.
     */
    fun clearQueue() {
        queue.clear()
        showing = false
    }
}
