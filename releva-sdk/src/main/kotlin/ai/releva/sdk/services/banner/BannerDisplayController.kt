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
    // DROP_LATEST rather than the default SUSPEND because showBanner is a plain function
    // called from a non-suspending trigger path; tryEmit on a SUSPEND flow is exactly what
    // dropped silently before. Not DROP_OLDEST: the producer emits in priority/server
    // order, so DROP_OLDEST would discard the highest-priority banners first, and it also
    // makes tryEmit's false-return case unreachable at this buffer size, which is what the
    // CHANGELOG's "a dropped item is logged" claim depends on being reachable. DROP_LATEST
    // keeps whatever is already buffered and refuses new arrivals once full, so the log
    // line below fires for real overflow past this capacity.
    private const val BUFFER_CAPACITY = 128

    private val _bannerFlow = MutableSharedFlow<BannerResponse>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )
    val bannerFlow: SharedFlow<BannerResponse> = _bannerFlow.asSharedFlow()

    fun showBanner(banner: BannerResponse) {
        // replay = 0, so a banner emitted before any host has called attach() is discarded
        // regardless of buffer size — tryEmit still returns true, and no buffer capacity
        // can fix it. Worth its own log line since it looks identical to a successful emit.
        if (_bannerFlow.subscriptionCount.value == 0) {
            Log.w(TAG, "Emitting banner ${banner.token} with no attached collector; it will be lost")
        }
        if (!_bannerFlow.tryEmit(banner)) {
            Log.w(TAG, "Dropped banner ${banner.token}: display buffer full ($BUFFER_CAPACITY)")
        }
    }
}
