package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
import android.util.Log

/**
 * Process-scoped hand-off of the banners that were on screen when a host was destroyed for a
 * configuration change, so the host that comes straight back can put them up again.
 *
 * It holds a single slot, written by `BannerDisplayManager.detach` only when
 * `Activity.isChangingConfigurations` is true, and emptied by the very next [take] — whether
 * that take matches or not. That is what keeps a recreation apart from a navigation: a
 * navigation never writes the slot (the activity is not changing configurations), and a
 * recreation's write is consumed or discarded by the first [attach][take] that follows it, so
 * nothing can still be sitting here to be restored onto a screen the user navigated to later.
 * Restoring a banner outside a recreation would re-show a banner that is already marked shown
 * in [BannerSessionStore], which is the once-per-session guarantee 1.5.1 exists to provide.
 *
 * The cost of the single slot is that two managers attached at once (two fragments in one
 * activity, say) retain over each other and at most one is restored. That direction is the
 * safe one: a banner that is not restored is the pre-1.5.2 behaviour, while a banner restored
 * onto the wrong screen is a regression.
 */
internal object BannerRetentionStore {
    private const val TAG = "BannerRetentionStore"

    private var hostKey: String? = null
    private var banners: List<BannerResponse> = emptyList()

    /** Stashes [banners] for [hostKey]'s next attach. An empty list just clears the slot. */
    fun retain(hostKey: String, banners: List<BannerResponse>) {
        if (banners.isEmpty()) {
            clear()
            return
        }
        Log.d(TAG, "Retaining ${banners.size} displayed banner(s) across recreation of $hostKey")
        this.hostKey = hostKey
        this.banners = banners
    }

    /** Returns and clears the slot if it belongs to [hostKey]; clears and returns empty if not. */
    fun take(hostKey: String): List<BannerResponse> {
        val retained = if (this.hostKey == hostKey) banners else emptyList()
        clear()
        return retained
    }

    fun clear() {
        hostKey = null
        banners = emptyList()
    }
}
