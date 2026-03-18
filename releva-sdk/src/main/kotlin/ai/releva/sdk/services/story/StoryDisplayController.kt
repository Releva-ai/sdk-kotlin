package ai.releva.sdk.services.story

import ai.releva.sdk.types.response.StoryResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SharedFlow-based controller for emitting story display events.
 * Consumers collect from [storyFlow] to show story viewer UI.
 */
object StoryDisplayController {
    private val _storyFlow = MutableSharedFlow<StoryResponse>(
        extraBufferCapacity = 10
    )
    val storyFlow: SharedFlow<StoryResponse> = _storyFlow.asSharedFlow()

    fun showStory(story: StoryResponse) {
        _storyFlow.tryEmit(story)
    }
}
