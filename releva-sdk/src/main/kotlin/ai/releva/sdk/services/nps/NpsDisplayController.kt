package ai.releva.sdk.services.nps

import ai.releva.sdk.types.response.NpsConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * SharedFlow-based controller for emitting NPS survey display events.
 * Consumers collect from [npsFlow] to show NPS UI.
 *
 * Uses replay = 1 so that an event emitted while the collector is paused
 * (e.g. when StoryViewerActivity is on top) is replayed when collection resumes.
 * Call [consumeNps] after handling the event to clear the replay cache.
 */
object NpsDisplayController {
    private val _npsFlow = MutableSharedFlow<NpsConfig>(
        replay = 1
    )
    val npsFlow: SharedFlow<NpsConfig> = _npsFlow.asSharedFlow()

    fun showNps(config: NpsConfig) {
        _npsFlow.tryEmit(config)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeNps() {
        _npsFlow.resetReplayCache()
    }
}
