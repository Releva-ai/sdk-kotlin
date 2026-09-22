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
 * The single slot also means [retain] refuses to overwrite one that is already held — first
 * writer wins, so two hosts writing at once (two managers in one host, or two live instances of
 * the same host class relaunched together, see `BannerDisplayManager.hostKey`) leave the second
 * write discarded rather than clobbering the first. The slot then goes to whichever attach
 * happens to come first and is emptied for everyone else, so at most one of the contenders is
 * ever restored — never zero-or-wrong, just zero-or-one. That direction is the safe one: a
 * banner that is not restored is the pre-1.5.2 behaviour, while a banner restored onto the
 * wrong screen is a regression.
 */
internal object BannerRetentionStore {
    private const val TAG = "BannerRetentionStore"

    private var hostKey: String? = null
    private var banners: List<BannerResponse> = emptyList()

    /**
     * Stashes [banners] for [hostKey]'s next attach. An empty list just clears the slot.
     *
     * Refuses to overwrite an already-held slot — first writer wins. Without this, two hosts
     * that write at (near) the same time, most notably two live instances of the same
     * Activity/Fragment class relaunched together by a configuration change, would let the
     * second write clobber the first, and the next matching attach could then be handed a
     * banner that belongs to a different instance. Dropping the second write instead means
     * that instance simply does not get its banner back — the same safe direction as the
     * two-managers-in-one-host case below.
     */
    fun retain(hostKey: String, banners: List<BannerResponse>) {
        if (banners.isEmpty()) {
            clear()
            return
        }
        if (this.hostKey != null) {
            Log.w(
                TAG,
                "Dropping ${banners.size} retained banner(s) for $hostKey: slot already held " +
                    "for ${this.hostKey} (first writer wins)"
            )
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
