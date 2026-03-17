package ai.releva.sdk.services.nps

import ai.releva.sdk.types.response.NpsConfig
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages NPS display timing and custom-event trigger evaluation.
 *
 * **Trigger responsibility split:**
 * - `appOpen`, `sessionCount`, and `screenView` triggers are evaluated server-side.
 *   If the server returned an `nps` field in the push response, a server-side trigger
 *   has already fired.
 * - `customEvent` triggers remain SDK-side: the SDK holds the config and waits for a
 *   matching [trackEvent] call before starting the delay timer.
 *
 * **Session-scoped suppression:** once the survey is shown or cancelled via a cancel
 * event, it will not show again until [startNewSession] is called.
 */
class NpsManagerService {

    private var config: NpsConfig? = null
    private var suppressedThisSession = false
    private var triggered = false
    private val handler = Handler(Looper.getMainLooper())
    private var delayRunnable: Runnable? = null

    /**
     * Called on every push response with the server's NPS config (or null).
     *
     * If the server returned a config, a server-side trigger has already fired.
     * The SDK will:
     * - Start the [triggerDelaySeconds] timer immediately if there are no
     *   customEvent triggers in the config.
     * - Otherwise hold the config and wait for a matching [trackEvent] call.
     */
    fun initialize(config: NpsConfig?) {
        this.config = config

        if (suppressedThisSession || config == null) return
        if (triggered) return  // Timer already running from a previous push call

        val hasCustomEventTriggers = config.triggers.any { it.type == "customEvent" }
        if (!hasCustomEventTriggers) {
            fireTrigger()
        }
    }

    /**
     * Called by RelevaClient.trackEvent(). Evaluates customEvent triggers
     * and cancel events.
     */
    fun trackEvent(eventName: String) {
        val cfg = config ?: return
        if (suppressedThisSession) return

        // Cancel events take priority
        if (cfg.cancelOnEvents.contains(eventName)) {
            Log.d(TAG, "Cancel event \"$eventName\" received — suppressing NPS this session")
            cancelDelayTimer()
            suppressedThisSession = true
            return
        }

        if (triggered) return

        // Check customEvent triggers
        for (trigger in cfg.triggers) {
            if (trigger.type == "customEvent" && trigger.eventName == eventName) {
                Log.d(TAG, "Custom event trigger \"$eventName\" matched")
                fireTrigger()
                return
            }
        }
    }

    private fun fireTrigger() {
        triggered = true
        val delay = config?.triggerDelaySeconds ?: 0
        Log.d(TAG, "Trigger fired, delay=${delay}s")

        val runnable = Runnable { showNps() }
        delayRunnable = runnable
        handler.postDelayed(runnable, delay * 1000L)
    }

    private fun showNps() {
        val cfg = config ?: return
        if (suppressedThisSession) return
        suppressedThisSession = true
        Log.d(TAG, "Showing NPS survey: ${cfg.token}")
        NpsDisplayController.showNps(cfg)
    }

    /**
     * Reset session-level state when a new NPS session begins.
     */
    fun startNewSession() {
        suppressedThisSession = false
        triggered = false
        cancelDelayTimer()
        Log.d(TAG, "New NPS session started")
    }

    fun dispose() {
        cancelDelayTimer()
    }

    private fun cancelDelayTimer() {
        delayRunnable?.let { handler.removeCallbacks(it) }
        delayRunnable = null
    }

    companion object {
        private const val TAG = "NpsManager"
    }
}
