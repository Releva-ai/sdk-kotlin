package ai.releva.sdk.ui.banner

import ai.releva.sdk.types.response.BannerResponse
import androidx.lifecycle.ViewModel

/**
 * Carries the banners that were on screen when a host was destroyed over to the instance that
 * replaces it.
 *
 * A [ViewModel] is the platform's own answer to "state that outlives a configuration change and
 * nothing else", so the scoping here is structural rather than a rule this SDK has to enforce:
 * a `ViewModelStore` belongs to one host *instance*, the platform hands it to the instance that
 * replaces it, and clears it when that host is really finished. Two live instances of the same
 * Activity class therefore have stores of their own, and restoring one instance's banners onto
 * another is not expressible.
 *
 * There is one of these per `BannerDisplayManager.targetSelector` within a host, keyed through
 * the `ViewModelProvider` key, so a host that attaches two managers watching different
 * selectors keeps a hand-off for each.
 *
 * Confined to the host's main thread: written from the host's `ON_DESTROY`, read from `attach`.
 */
internal class BannerRetentionViewModel : ViewModel() {

    private var retained: List<BannerResponse> = emptyList()

    fun retain(banners: List<BannerResponse>) {
        retained = banners
    }

    /**
     * Returns what was retained and empties the slot, so one hand-off is restored exactly once
     * even if the host attaches more than one manager for the same selector.
     */
    fun take(): List<BannerResponse> {
        val banners = retained
        retained = emptyList()
        return banners
    }
}
