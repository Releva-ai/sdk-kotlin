package ai.releva.sdk.services.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.BannerResponse
import android.util.Log
import kotlinx.coroutines.*

/**
 * Manages banner trigger logic and lifecycle.
 * Handles trigger types: immediately, delaySeconds, scrollPercentage, cartChanged, wishlistChanged.
 *
 * Which banners have already been displayed is kept in [BannerSessionStore], not here, so a
 * banner shows once per session rather than once per push response. This class only decides
 * when to *emit* a banner into [BannerDisplayController]; it does not mark a token as shown.
 * Emitting is not the same as displaying — there may be no attached collector, the display
 * buffer may be full, or the attached [ai.releva.sdk.ui.banner.BannerDisplayManager] may filter
 * the banner out (wrong `cssSelector`, `displayType == "custom"`, no `design`). Marking happens
 * on the display side, in `BannerDisplayManager.showBanner`, which is the only place that knows
 * a banner actually rendered — so a banner that was emitted but never shown is retried on the
 * next trigger instead of being suppressed for the rest of the session.
 */
class BannerManagerService {
    private val banners = mutableListOf<BannerResponse>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scrollPercentageProvider: (() -> Int)? = null
    private var cartChangeCallback: (() -> Unit)? = null
    private var wishlistChangeCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "BannerManagerService"
    }

    /**
     * Initialize with banners from a push response.
     * Replaces the banner list, cancels the previous response's timers and sets up triggers
     * for each banner. The set of already displayed banners is session-scoped and survives
     * this call.
     */
    fun initialize(
        newBanners: List<BannerResponse>,
        scrollPercentageProvider: (() -> Int)? = null
    ) {
        Log.d(TAG, "initialize called with ${newBanners.size} banners")
        dispose()

        banners.clear()
        banners.addAll(newBanners)
        this.scrollPercentageProvider = scrollPercentageProvider

        setupTriggers()
    }

    private fun setupTriggers() {
        for (banner in banners) {
            when (banner.trigger) {
                "immediately" -> triggerBanner(banner)

                "delaySeconds" -> {
                    val delay = banner.delaySeconds
                    if (delay != null) {
                        scope.launch {
                            delay(delay * 1000L)
                            triggerBanner(banner)
                        }
                    }
                }

                "scrollPercentage" -> {
                    if (banner.scrollPercentage != null && scrollPercentageProvider != null) {
                        setupScrollTrigger(banner)
                    }
                }

                "cartChanged" -> {
                    // Cart trigger will be handled via onCartChanged()
                    Log.d(TAG, "Cart trigger registered for banner: ${banner.token}")
                }

                "wishlistChanged" -> {
                    // Wishlist trigger will be handled via onWishlistChanged()
                    Log.d(TAG, "Wishlist trigger registered for banner: ${banner.token}")
                }

                "leaveIntent" -> {
                    Log.d(TAG, "LeaveIntent trigger not supported on mobile platforms")
                }
            }
        }
    }

    private fun setupScrollTrigger(banner: BannerResponse) {
        val provider = scrollPercentageProvider ?: return
        val threshold = banner.scrollPercentage ?: return
        if (BannerSessionStore.isShown(banner.token)) {
            Log.d(TAG, "Scroll banner ${banner.token} already shown this session, not polling")
            return
        }

        scope.launch {
            while (isActive) {
                delay(500) // Poll every 500ms
                if (BannerSessionStore.isShown(banner.token)) break
                if (provider() >= threshold) {
                    triggerBanner(banner)
                    break
                }
            }
        }
    }

    /**
     * Call this when the cart changes to trigger cart-based banners.
     */
    fun onCartChanged() {
        banners.filter { it.trigger == "cartChanged" }.forEach { banner ->
            triggerBanner(banner)
        }
    }

    /**
     * Call this when the wishlist changes to trigger wishlist-based banners.
     */
    fun onWishlistChanged() {
        banners.filter { it.trigger == "wishlistChanged" }.forEach { banner ->
            triggerBanner(banner)
        }
    }

    private fun triggerBanner(banner: BannerResponse) {
        Log.d(TAG, "triggerBanner called for banner: ${banner.token}, trigger: ${banner.trigger}")
        if (!BannerSessionStore.isShown(banner.token)) {
            Log.d(TAG, "Banner not shown yet this session, emitting it for display")
            // Do not mark shown here: emitting into BannerDisplayController is not the same as
            // displaying it (no attached collector, a full buffer, or the display side's own
            // filtering can all drop it silently). The display side marks it once it actually
            // renders, so a banner that was dropped rather than shown is retried the next time
            // this is called instead of being suppressed for the rest of the session.
            BannerDisplayController.showBanner(banner)
        } else {
            Log.d(TAG, "Banner already shown this session, skipping")
        }
    }

    fun dispose() {
        scope.coroutineContext.cancelChildren()
    }
}
