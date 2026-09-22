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
        // The shown-token set is session-scoped and shared by every instance, so tests that
        // reuse a token need it reset first.
        BannerSessionStore.startNewSession()
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
    fun `onCartChanged does not re-trigger a banner the display side already marked shown`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "cart-once", trigger = "cartChanged")
        service.initialize(listOf(banner))

        service.onCartChanged()
        assertEquals(1, emitted.size)
        // triggerBanner only emits; BannerDisplayManager.showBanner is what marks a token shown
        // once it actually renders. Simulate that here.
        BannerSessionStore.markShown(banner.token)

        // Second cart change should not re-trigger a banner already marked shown
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
    fun `onWishlistChanged does not re-trigger a banner the display side already marked shown`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "wish-once", trigger = "wishlistChanged")
        service.initialize(listOf(banner))

        service.onWishlistChanged()
        assertEquals(1, emitted.size)
        // triggerBanner only emits; BannerDisplayManager.showBanner is what marks a token shown
        // once it actually renders. Simulate that here.
        BannerSessionStore.markShown(banner.token)

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
    fun `reinitialize does not re-display a banner the display side already marked shown`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "reset-1", trigger = "immediately")
        // The host app calls initialize() on every push response carrying banners, so the
        // same banner arrives again on the next screen view.
        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)
        // triggerBanner only emits; BannerDisplayManager.showBanner is what marks a token shown
        // once it actually renders. Simulate that here.
        BannerSessionStore.markShown(banner.token)

        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `a banner emitted but never marked shown is retried on the next initialize`() = runTest {
        // Regression test for the drop path: initialize() can emit into
        // BannerDisplayController before any BannerDisplayManager has attached (cold start, or
        // the ON_PAUSE/ON_RESUME gap on a rotation), a full display buffer can drop it, or the
        // attached manager's own shouldDisplayBanner filter can discard it (no design, a custom
        // displayType, or a static banner whose cssSelector doesn't match). None of those are
        // "displayed", so triggerBanner must not have marked the token shown, and the same
        // banner arriving in the next push response must be retried rather than suppressed for
        // the rest of the session.
        val banner = BannerResponse(token = "no-collector", trigger = "immediately")

        // No collector attached yet — nothing marks this shown.
        service.initialize(listOf(banner))
        assertFalse(BannerSessionStore.isShown(banner.token))

        // The next screen view calls initialize() again with the same banner, now with a
        // collector attached (simulating a BannerDisplayManager on the new screen).
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }
        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)
        assertEquals("no-collector", emitted[0].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `new session allows a banner to display again`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "session-1", trigger = "immediately")
        service.initialize(listOf(banner))
        assertEquals(1, emitted.size)
        // Simulate the display side actually rendering it — without this, the token would
        // never be marked shown and startNewSession() below wouldn't be what re-enables it.
        BannerSessionStore.markShown(banner.token)

        BannerSessionStore.startNewSession()
        service.initialize(listOf(banner))

        assertEquals(2, emitted.size)
        assertEquals("session-1", emitted[1].token)

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

    @Test
    fun `scrollPercentage trigger does not poll for a banner already shown this session`() = runTest {
        val emitted = mutableListOf<BannerResponse>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            BannerDisplayController.bannerFlow.collect { emitted.add(it) }
        }

        val banner = BannerResponse(token = "scroll-already-shown", trigger = "scrollPercentage", scrollPercentage = 50)
        BannerSessionStore.markShown(banner.token)

        // Provider already past threshold — if setupScrollTrigger scheduled a poll instead of
        // bailing out early, this would still not emit (isShown gates triggerBanner too), but it
        // would leave a coroutine polling forever. This test only pins the no-emit behaviour;
        // the non-scheduling is what stops the runaway poll.
        service.initialize(listOf(banner), scrollPercentageProvider = { 100 })

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `scrollPercentage trigger does not schedule a poll for a banner already shown`() {
        // The test above passes identically whether or not setupScrollTrigger's early return
        // exists, because triggerBanner's own isShown check would suppress the emission either
        // way — it only pins the no-emit behaviour a caller sees, not the absence of a runaway
        // poll. This one counts provider invocations and advances Robolectric's paused main
        // looper past the poll's 500ms tick: if the early return were removed, the scheduled
        // poll would call the provider once it fires and this would go red.
        val banner = BannerResponse(token = "scroll-no-poll", trigger = "scrollPercentage", scrollPercentage = 50)
        BannerSessionStore.markShown(banner.token)

        var providerCalls = 0
        service.initialize(listOf(banner), scrollPercentageProvider = { providerCalls++; 100 })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(600))

        assertEquals(0, providerCalls)

        service.dispose()
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
