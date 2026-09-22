package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
import android.util.Log

/**
 * Process-scoped hand-off of the banners that were on screen when a host was destroyed for a
 * configuration change, so the host that comes straight back can put them up again.
 *
 * Entries are written by `BannerDisplayManager.detach` only when
 * `Activity.isChangingConfigurations` is true, and the whole store is emptied by the very next
 * [take] — whether that take found an entry of its own or not. That is what keeps a recreation
 * apart from a navigation: a navigation never writes here (the host is not changing
 * configurations), and a recreation's write is consumed or discarded by the first
 * `attach()`/[take] that follows it, so nothing can still be sitting here to be restored onto a
 * screen the user navigates to later. Restoring outside a recreation would re-show a banner that
 * is already marked shown in [BannerSessionStore], which is the once-per-session guarantee 1.5.1
 * exists to provide.
 *
 * Entries are keyed by `BannerDisplayManager.hostKey`, so hosts destroyed by the same
 * configuration change can neither take each other's banners nor discard each other's entries:
 * [take] only ever returns what was stored under the caller's own key, and one host's hand-off
 * only ever replaces its own. The one thing the key cannot resolve is two *live* instances of
 * the same host class with the same selector, which share a key — [take] cannot tell their
 * recreations apart either, so a second [retain] under a key that is already claimed empties
 * that key instead of keeping either side, and neither instance restores, whichever of them
 * attaches first.
 *
 * Because the emptying rule above is "first take wins", at most one host is restored per
 * configuration change, and never a wrong one.
 *
 * [retain], [take] and [clear] are `@Synchronized`. They normally all run on the main thread
 * from `BannerDisplayManager`, but [clear] is also reachable from
 * `SessionService.startNewSession()`, which can run on `Dispatchers.IO` — see the comment
 * there. The monitor gives that cross-thread [clear] both the happens-before edge the next
 * [take] needs and mutual exclusion against a [retain]'s read-then-write, which a `@Volatile`
 * field alone would not. The critical sections are a lookup or a clear of a map with an entry
 * or two in it, on a path that runs once per screen teardown or attach.
 */
internal object BannerRetentionStore {
    private const val TAG = "BannerRetentionStore"

    private val retained = mutableMapOf<String, List<BannerResponse>>()

    /**
     * Stashes [banners] for [hostKey]'s next attach. An empty list claims the key with nothing
     * to restore, which is what makes the same-key collision described above resolve the same
     * way whichever of the two instances happened to have a banner up.
     */
    @Synchronized
    fun retain(hostKey: String, banners: List<BannerResponse>) {
        val alreadyClaimed = retained.put(hostKey, banners) != null
        if (alreadyClaimed) {
            // A second writer under the *same* key cannot be told apart from the first by
            // take(), so keeping either one risks handing it to the wrong instance. Drop both.
            retained[hostKey] = emptyList()
            Log.w(
                TAG,
                "Dropping ${banners.size} retained banner(s): $hostKey was already claimed by " +
                    "another live instance of the same host (dropping both)"
            )
        } else if (banners.isEmpty()) {
            Log.d(TAG, "Nothing on screen to retain across recreation of $hostKey")
        } else {
            Log.d(TAG, "Retaining ${banners.size} displayed banner(s) across recreation of $hostKey")
        }
    }

    /**
     * Returns the banners retained for [hostKey], if any, and empties the store — every entry,
     * not only this key, so that nothing outlives the first attach after a recreation.
     */
    @Synchronized
    fun take(hostKey: String): List<BannerResponse> {
        val retainedForHost = retained[hostKey] ?: emptyList()
        retained.clear()
        return retainedForHost
    }

    @Synchronized
    fun clear() {
        retained.clear()
    }
}
