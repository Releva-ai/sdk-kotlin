package ai.releva.sdk.services.story

import ai.releva.sdk.types.response.StoryResponse
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SharedFlow-based controller for emitting story display events.
 * Consumers collect from [storyFlow] to show story viewer UI.
 */
object StoryDisplayController {
    private const val TAG = "StoryDisplayCtrl"

    // Same shape as BannerDisplayController: a synchronous producer and a consumer that
    // launches an Activity per story. A page with more stories than the buffer holds lost
    // the tail with no log line. Fewer stories than banners are realistic on one page, so
    // this was latent rather than observed — fixed together because it is the same defect.
    private const val BUFFER_CAPACITY = 64

    private val _storyFlow = MutableSharedFlow<StoryResponse>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val storyFlow: SharedFlow<StoryResponse> = _storyFlow.asSharedFlow()

    fun showStory(story: StoryResponse) {
        if (!_storyFlow.tryEmit(story)) {
            Log.w(TAG, "Dropped story ${story.token}: display buffer full ($BUFFER_CAPACITY)")
        }
    }
}
