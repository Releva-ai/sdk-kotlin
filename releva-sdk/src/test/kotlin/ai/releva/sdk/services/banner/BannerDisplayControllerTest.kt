package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
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
class BannerDisplayControllerTest {

    @Test
    fun `showBanner emits banner to flow`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "ctrl-1", trigger = "immediately")
        BannerDisplayController.showBanner(banner)

        assertEquals(1, emitted.size)
        assertEquals("ctrl-1", emitted[0].token)

        job.cancel()
    }

    @Test
    fun `showBanner emits multiple banners in order`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        BannerDisplayController.showBanner(BannerResponse(token = "first"))
        BannerDisplayController.showBanner(BannerResponse(token = "second"))
        BannerDisplayController.showBanner(BannerResponse(token = "third"))

        assertEquals(3, emitted.size)
        assertEquals("first", emitted[0].token)
        assertEquals("second", emitted[1].token)
        assertEquals("third", emitted[2].token)

        job.cancel()
    }

    @Test
    fun `bannerFlow is shared - multiple collectors receive same events`() = runTest {
        val collector1 = mutableListOf<BannerResponse>()
        val collector2 = mutableListOf<BannerResponse>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { collector1.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { collector2.add(it) }
        }

        BannerDisplayController.showBanner(BannerResponse(token = "shared-1"))

        assertEquals(1, collector1.size)
        assertEquals(1, collector2.size)
        assertEquals("shared-1", collector1[0].token)
        assertEquals("shared-1", collector2[0].token)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `showBanner preserves all banner fields`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(
            token = "full-banner",
            bannerId = 42,
            segmentId = 7,
            name = "Full Banner",
            html = "<div>Hello</div>",
            displayType = "popup",
            trigger = "immediately",
            delaySeconds = 5,
            scrollPercentage = 50,
            cssSelector = "#target"
        )
        BannerDisplayController.showBanner(banner)

        assertEquals(1, emitted.size)
        val received = emitted[0]
        assertEquals("full-banner", received.token)
        assertEquals(42, received.bannerId)
        assertEquals(7, received.segmentId)
        assertEquals("Full Banner", received.name)
        assertEquals("<div>Hello</div>", received.html)
        assertEquals("popup", received.displayType)
        assertEquals("immediately", received.trigger)
        assertEquals(5, received.delaySeconds)
        assertEquals(50, received.scrollPercentage)
        assertEquals("#target", received.cssSelector)

        job.cancel()
    }
}
