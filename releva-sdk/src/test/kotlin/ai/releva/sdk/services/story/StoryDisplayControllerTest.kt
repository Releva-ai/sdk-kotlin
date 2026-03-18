package ai.releva.sdk.services.story

import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.response.StorySlideResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoryDisplayControllerTest {

    @Test
    fun `showStory emits to flow`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        val story = StoryResponse(
            token = "test-story",
            slides = listOf(StorySlideResponse(id = 1, durationSeconds = 5))
        )
        StoryDisplayController.showStory(story)

        assertEquals(1, emitted.size)
        assertEquals("test-story", emitted[0].token)

        job.cancel()
    }

    @Test
    fun `multiple stories emitted in order`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        for (i in 1..5) {
            StoryDisplayController.showStory(
                StoryResponse(token = "story-$i", slides = listOf(StorySlideResponse(id = i, durationSeconds = 5)))
            )
        }

        assertEquals(5, emitted.size)
        assertEquals("story-1", emitted[0].token)
        assertEquals("story-5", emitted[4].token)

        job.cancel()
    }

    @Test
    fun `story fields preserved through emission`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        val story = StoryResponse(
            token = "rich-story",
            storyId = 42,
            name = "Test Story",
            trigger = "immediately",
            endBehavior = "loop",
            progressIndicatorColor = "#FF0000",
            tags = listOf("tag1", "tag2"),
            slides = listOf(
                StorySlideResponse(id = 1, durationSeconds = 10, actionLabel = "Click"),
                StorySlideResponse(id = 2, durationSeconds = 5)
            )
        )
        StoryDisplayController.showStory(story)

        assertEquals(1, emitted.size)
        val received = emitted[0]
        assertEquals("rich-story", received.token)
        assertEquals(42, received.storyId)
        assertEquals("loop", received.endBehavior)
        assertEquals(2, received.slides.size)
        assertEquals("Click", received.slides[0].actionLabel)

        job.cancel()
    }
}
