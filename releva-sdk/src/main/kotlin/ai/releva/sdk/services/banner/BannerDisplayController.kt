package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton controller that manages banner display events via a SharedFlow.
 * Banners are emitted here by BannerManagerService and consumed by BannerDisplayManager.
 */
object BannerDisplayController {
    private const val TAG = "BannerDisplayCtrl"

    // The producer emits every `immediately` banner in one synchronous pass, while the
    // consumer renders them one at a time — inflating views, decoding images, showing
    // dialogs. The consumer is orders of magnitude slower, so the buffer has to hold a
    // whole page's worth of banners or the tail is discarded.
    //
    // Measured on a device against a page with 22 banner blocks: 19 were emitted, 12
    // rendered, and the 7 the buffer could not hold vanished with no log line — no error,
    // no impression, nothing to tell a tester the page was incomplete. The old capacity
    // was 10.
    //
    // DROP_OLDEST rather than the default SUSPEND because showBanner is a plain function
    // called from a non-suspending trigger path; tryEmit on a SUSPEND flow is exactly what
    // dropped silently before. If this capacity is ever exceeded, losing the oldest and
    // saying so is the honest failure.
    private const val BUFFER_CAPACITY = 128

    private val _bannerFlow = MutableSharedFlow<BannerResponse>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bannerFlow: SharedFlow<BannerResponse> = _bannerFlow.asSharedFlow()

    fun showBanner(banner: BannerResponse) {
        // tryEmit's result was previously discarded. With DROP_OLDEST it cannot return
        // false, but the check stays: if the flow's configuration is ever changed back to
        // a suspending overflow policy, a silent drop would return here rather than
        // disappear.
        if (!_bannerFlow.tryEmit(banner)) {
            Log.w(TAG, "Dropped banner ${banner.token}: display buffer full ($BUFFER_CAPACITY)")
        }
    }
}
