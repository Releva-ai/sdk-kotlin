package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.story.StoryDisplayController
import ai.releva.sdk.types.response.StoryResponse
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Collects from [StoryDisplayController.storyFlow] and launches [StoryViewerActivity].
 * Call [attach] from your Activity's onCreate().
 *
 * Both [client] and [onLinkTap] must be set before calling [attach].
 */
object StoryDisplayManager {

    private var client: RelevaClient? = null
    private var onLinkTap: ((String) -> Unit)? = null

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

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StoryDisplayController.storyFlow.collect { story ->
                    showStory(activity, story)
                }
            }
        }
    }

    private fun showStory(activity: FragmentActivity, story: StoryResponse) {
        val cl = client ?: return
        StoryViewerActivity.launch(
            context = activity,
            story = story,
            client = cl,
            onLinkTap = onLinkTap
        )
    }
}
