package ai.releva.sdk.services.banner

import ai.releva.sdk.types.response.BannerResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton controller that manages banner display events via a SharedFlow.
 * Banners are emitted here by BannerManagerService and consumed by BannerDisplayManager.
 */
object BannerDisplayController {
    private val _bannerFlow = MutableSharedFlow<BannerResponse>(extraBufferCapacity = 10)
    val bannerFlow: SharedFlow<BannerResponse> = _bannerFlow.asSharedFlow()

    fun showBanner(banner: BannerResponse) {
        _bannerFlow.tryEmit(banner)
    }
}
