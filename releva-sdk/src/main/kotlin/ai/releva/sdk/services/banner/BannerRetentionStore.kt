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
 * The single slot means two hosts writing at (near) the same time collide, and [take] can only
 * tell the contenders apart by [hostKey] — never by instance identity — so the two collision
 * shapes have to be handled differently:
 *  - **Same key** (two live instances of the same host class relaunched together by one
 *    configuration change, see `BannerDisplayManager.hostKey`): [take] cannot distinguish them,
 *    so keeping *either* side risks handing it to the wrong instance. [retain] drops **both** —
 *    the first writer's entry is cleared when the second, same-key write arrives.
 *  - **Different keys** (two unrelated managers, e.g. an Activity and a Fragment, writing at
 *    once): [take] cannot misattribute across keys — a mismatched key always empties the slot
 *    and returns nothing — so it is safe to leave the first writer's entry alone and just drop
 *    the second: first-writer-wins.
 * Either way, at most one host is ever restored, and never the wrong one.
 *
 * [hostKey] and [banners] are `@Volatile`: [retain]/[take]/[clear] are normally all called on
 * the main thread (from `BannerDisplayManager`), but [clear] is also reachable from
 * `SessionService.startNewSession()`, which can run on `Dispatchers.IO` — see the comment there.
 * `@Volatile` gives that cross-thread [clear] a happens-before edge to the next main-thread
 * [take]. [retain] writes [banners] before [hostKey] so that once a reader observes the new
 * [hostKey], [banners] is guaranteed to already hold the matching value.
 */
internal object BannerRetentionStore {
    private const val TAG = "BannerRetentionStore"

    @Volatile private var hostKey: String? = null
    @Volatile private var banners: List<BannerResponse> = emptyList()

    /**
     * Stashes [banners] for [hostKey]'s next attach. An empty list just clears the slot.
     *
     * Two hosts writing at (near) the same time collide; see the class doc for why a same-key
     * collision drops both sides while a different-key collision keeps first-writer-wins.
     */
    fun retain(hostKey: String, banners: List<BannerResponse>) {
        if (banners.isEmpty()) {
            clear()
            return
        }
        val held = this.hostKey
        if (held != null) {
            if (held == hostKey) {
                // A second writer under the *same* key cannot be told apart from the first by
                // take(), so keeping either one risks handing it to the wrong instance later.
                // Drop both.
                Log.w(TAG, "Dropping retained banner(s) for $hostKey: same-key collision with $held (dropping both)")
                clear()
            } else {
                Log.w(
                    TAG,
                    "Dropping ${banners.size} retained banner(s) for $hostKey: slot already held " +
                        "for $held (first writer wins)"
                )
            }
            return
        }
        Log.d(TAG, "Retaining ${banners.size} displayed banner(s) across recreation of $hostKey")
        this.banners = banners
        this.hostKey = hostKey
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
