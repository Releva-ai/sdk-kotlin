package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class BannerManagerServiceTest {

    private lateinit var service: BannerManagerService

    @Before
    fun setUp() {
        service = BannerManagerService()
    }

    // Immediately Trigger Tests

    @Test
    fun `initialize with immediately trigger emits banner`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "imm-1", trigger = "immediately")
        service.initialize(listOf(banner))

        assertEquals(1, emitted.size)
        assertEquals("imm-1", emitted[0].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `initialize with multiple immediately triggers emits all`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banners = listOf(
            BannerResponse(token = "imm-1", trigger = "immediately"),
            BannerResponse(token = "imm-2", trigger = "immediately"),
            BannerResponse(token = "imm-3", trigger = "immediately")
        )
        service.initialize(banners)

        assertEquals(3, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `immediately trigger does not emit same banner twice`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "imm-once", trigger = "immediately")
        service.initialize(listOf(banner))

        // Only 1 emission even though trigger is immediate
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Cart Changed Trigger Tests

    @Test
    fun `onCartChanged triggers cart banners`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banners = listOf(
            BannerResponse(token = "cart-1", trigger = "cartChanged"),
            BannerResponse(token = "imm-1", trigger = "immediately")
        )
        service.initialize(banners)

        // Only immediately banner should have fired
        assertEquals(1, emitted.size)
        assertEquals("imm-1", emitted[0].token)

        // Now trigger cart change
        service.onCartChanged()

        assertEquals(2, emitted.size)
        assertEquals("cart-1", emitted[1].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `onCartChanged does not re-trigger already displayed banner`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "cart-once", trigger = "cartChanged")
        service.initialize(listOf(banner))

        service.onCartChanged()
        assertEquals(1, emitted.size)

        // Second cart change should not re-trigger
        service.onCartChanged()
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Wishlist Changed Trigger Tests

    @Test
    fun `onWishlistChanged triggers wishlist banners`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "wish-1", trigger = "wishlistChanged")
        service.initialize(listOf(banner))

        service.onWishlistChanged()

        assertEquals(1, emitted.size)
        assertEquals("wish-1", emitted[0].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `onWishlistChanged does not re-trigger already displayed banner`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "wish-once", trigger = "wishlistChanged")
        service.initialize(listOf(banner))

        service.onWishlistChanged()
        assertEquals(1, emitted.size)

        service.onWishlistChanged()
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Mixed Trigger Tests

    @Test
    fun `mixed triggers only fire their respective events`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banners = listOf(
            BannerResponse(token = "imm-1", trigger = "immediately"),
            BannerResponse(token = "cart-1", trigger = "cartChanged"),
            BannerResponse(token = "wish-1", trigger = "wishlistChanged")
        )
        service.initialize(banners)

        // Only immediately should fire
        assertEquals(1, emitted.size)
        assertEquals("imm-1", emitted[0].token)

        // Cart change fires cart banner
        service.onCartChanged()
        assertEquals(2, emitted.size)
        assertEquals("cart-1", emitted[1].token)

        // Wishlist change fires wishlist banner
        service.onWishlistChanged()
        assertEquals(3, emitted.size)
        assertEquals("wish-1", emitted[2].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `onCartChanged does not trigger wishlist banners`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "wish-only", trigger = "wishlistChanged")
        service.initialize(listOf(banner))

        service.onCartChanged()

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `onWishlistChanged does not trigger cart banners`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "cart-only", trigger = "cartChanged")
        service.initialize(listOf(banner))

        service.onWishlistChanged()

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Initialize / Reset Tests

    @Test
    fun `reinitialize clears displayed banners and allows re-display`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "reset-1", trigger = "immediately")
        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)

        // Re-initialize with same banner - should emit again
        service.initialize(listOf(banner))
        assertEquals(2, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `initialize with empty list does not emit`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        service.initialize(emptyList())

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Unsupported Trigger Tests

    @Test
    fun `leaveIntent trigger is ignored`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "leave-1", trigger = "leaveIntent")
        service.initialize(listOf(banner))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `unknown trigger type is ignored`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "unknown-1", trigger = "someFutureTrigger")
        service.initialize(listOf(banner))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `null trigger is ignored`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "null-trigger", trigger = null)
        service.initialize(listOf(banner))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    // DelaySeconds Trigger Tests

    @Test
    fun `delaySeconds trigger without delay value does not emit`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "delay-no-val", trigger = "delaySeconds", delaySeconds = null)
        service.initialize(listOf(banner))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    // ScrollPercentage Trigger Tests

    @Test
    fun `scrollPercentage trigger without provider does not emit`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "scroll-no-provider", trigger = "scrollPercentage", scrollPercentage = 50)
        // No scrollPercentageProvider passed
        service.initialize(listOf(banner))

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `scrollPercentage trigger without percentage value does not emit`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "scroll-no-pct", trigger = "scrollPercentage", scrollPercentage = null)
        service.initialize(listOf(banner), scrollPercentageProvider = { 100 })

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    // Dispose Tests

    @Test
    fun `dispose can be called multiple times safely`() {
        service.dispose()
        service.dispose()
        service.dispose()
        // No exception thrown
    }

    @Test
    fun `dispose then reinitialize works`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "dispose-reinit", trigger = "immediately")
        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)

        service.dispose()

        service.initialize(listOf(BannerResponse(token = "after-dispose", trigger = "immediately")))
        assertEquals(2, emitted.size)
        assertEquals("after-dispose", emitted[1].token)

        service.dispose()
        job.cancel()
    }
}
