package ai.releva.sdk.services.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.BannerResponse
import android.util.Log
import kotlinx.coroutines.*

/**
 * Manages banner trigger logic and lifecycle.
 * Handles trigger types: immediately, delaySeconds, scrollPercentage, cartChanged, wishlistChanged.
 */
class BannerManagerService {
    private val banners = mutableListOf<BannerResponse>()
    private val displayedBanners = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scrollPercentageProvider: (() -> Int)? = null
    private var cartChangeCallback: (() -> Unit)? = null
    private var wishlistChangeCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "BannerManagerService"
    }

    /**
     * Initialize with banners from a push response.
     * Clears previous state and sets up triggers for each banner.
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

        Log.d(TAG, "Clearing displayedBanners (had ${displayedBanners.size} entries)")
        displayedBanners.clear()

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

        scope.launch {
            while (isActive) {
                delay(500) // Poll every 500ms
                val currentPercent = provider()
                if (currentPercent >= threshold && !displayedBanners.contains(banner.token)) {
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
        if (!displayedBanners.contains(banner.token)) {
            Log.d(TAG, "Banner not in displayedBanners, showing it")
            displayedBanners.add(banner.token)
            BannerDisplayController.showBanner(banner)
        } else {
            Log.d(TAG, "Banner already in displayedBanners, skipping")
        }
    }

    fun dispose() {
        scope.coroutineContext.cancelChildren()
    }
}
