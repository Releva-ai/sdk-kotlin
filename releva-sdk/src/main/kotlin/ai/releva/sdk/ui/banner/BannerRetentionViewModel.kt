package ai.releva.sdk.ui.banner

import ai.releva.sdk.types.response.BannerResponse
import androidx.lifecycle.ViewModel

/**
 * Carries the banners that were on screen when a host was destroyed for a configuration change
 * over to the instance that immediately replaces it.
 *
 * A [ViewModel] is the platform's own answer to "state that outlives a configuration change and
 * nothing else", which is exactly the distinction this fix turns on:
 *
 *  - a configuration change hands the host's `ViewModelStore` to the new instance, so the
 *    banners are still here when it attaches;
 *  - a genuine destroy clears the store, and a navigation to another screen is a different host
 *    instance with a store of its own. Neither can reach a retained banner, which is what keeps
 *    the once-per-session guarantee of 1.5.1 (CHANGELOG "BAN-13") intact — the restore path is
 *    not reachable from a navigation at all, rather than being made unreachable by a rule this
 *    SDK has to enforce.
 *
 * Scope follows from where it is stored: per host instance via the store owner, and per screen
 * within that host via the `ViewModelProvider` key (`BannerDisplayManager.targetSelector`). Two
 * fragments in one activity, two managers with different selectors, and two live instances of
 * the same Activity class therefore each get their own — none of them can see, take or discard
 * another's, and all of them restore from the same configuration change.
 *
 * Confined to the host's main thread: written from its `ON_DESTROY`, read from its `attach`.
 */
internal class BannerRetentionViewModel : ViewModel() {

    private var retained: List<BannerResponse> = emptyList()

    fun retain(banners: List<BannerResponse>) {
        retained = banners
    }

    /**
     * Returns what was retained and empties the slot, so one hand-off is restored exactly once
     * even if the host attaches a manager more than one time.
     */
    fun take(): List<BannerResponse> {
        val banners = retained
        retained = emptyList()
        return banners
    }
}
