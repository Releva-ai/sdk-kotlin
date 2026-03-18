package ai.releva.sdk.services.nps

import ai.releva.sdk.types.response.NpsConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SharedFlow-based controller for emitting NPS survey display events.
 * Consumers collect from [npsFlow] to show NPS UI.
 */
object NpsDisplayController {
    private val _npsFlow = MutableSharedFlow<NpsConfig>(
        extraBufferCapacity = 5
    )
    val npsFlow: SharedFlow<NpsConfig> = _npsFlow.asSharedFlow()

    fun showNps(config: NpsConfig) {
        _npsFlow.tryEmit(config)
    }
}
