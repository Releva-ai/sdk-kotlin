package ai.releva.sdk.services.story

import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.response.StorySlideResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StoryManagerServiceTest {

    private lateinit var service: StoryManagerService

    private fun makeStory(
        token: String,
        trigger: String = "immediately",
        delaySeconds: Int? = null,
        scrollPercentage: Int? = null,
        slides: List<StorySlideResponse> = listOf(StorySlideResponse(id = 1, durationSeconds = 5))
    ) = StoryResponse(
        token = token,
        trigger = trigger,
        delaySeconds = delaySeconds,
        scrollPercentage = scrollPercentage,
        slides = slides
    )

    @Before
    fun setUp() {
        service = StoryManagerService()
    }

    @Test
    fun `initialize with immediately trigger emits story`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1")))

        assertEquals(1, emitted.size)
        assertEquals("s1", emitted[0].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `initialize with multiple immediately triggers emits all`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(
            makeStory("s1"),
            makeStory("s2"),
            makeStory("s3")
        ))

        assertEquals(3, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `story with empty slides is skipped`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1", slides = emptyList())))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `cartChanged trigger fires on onCartChanged`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1", trigger = "cartChanged")))
        assertEquals(0, emitted.size)

        service.onCartChanged()
        assertEquals(1, emitted.size)
        assertEquals("s1", emitted[0].token)

        // Calling again should not re-emit (already displayed)
        service.onCartChanged()
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `wishlistChanged trigger fires on onWishlistChanged`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1", trigger = "wishlistChanged")))
        assertEquals(0, emitted.size)

        service.onWishlistChanged()
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `scrollPercentage trigger fires when threshold reached`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1", trigger = "scrollPercentage", scrollPercentage = 50)))
        assertEquals(0, emitted.size)

        service.onScrollPercentageReached(30)
        assertEquals(0, emitted.size)

        service.onScrollPercentageReached(50)
        assertEquals(1, emitted.size)

        // Already displayed
        service.onScrollPercentageReached(80)
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `re-initialize clears previous stories`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1")))
        assertEquals(1, emitted.size)

        service.initialize(listOf(makeStory("s2")))
        assertEquals(2, emitted.size)
        assertEquals("s2", emitted[1].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `leaveIntent trigger is ignored`() = runTest {
        val emitted = mutableListOf<StoryResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StoryDisplayController.storyFlow.collect { emitted.add(it) }
        }

        service.initialize(listOf(makeStory("s1", trigger = "leaveIntent")))
        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }
}
