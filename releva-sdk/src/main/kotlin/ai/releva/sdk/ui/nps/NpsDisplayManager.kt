package ai.releva.sdk.ui.nps

import ai.releva.sdk.services.nps.NpsDisplayController
import ai.releva.sdk.types.response.NpsConfig
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Collects from [NpsDisplayController.npsFlow] and shows [NpsDialogFragment].
 * Call [attach] from your Activity's onCreate().
 */
object NpsDisplayManager {

    private var onSubmit: (suspend (String, Int, String?) -> Unit)? = null
    private var onSkip: (() -> Unit)? = null

    /**
     * Set the submission callback. Called when user completes the NPS survey.
     */
    fun setOnSubmit(onSubmit: suspend (String, Int, String?) -> Unit) {
        this.onSubmit = onSubmit
    }

    /**
     * Set the skip callback. Called when user taps skip.
     */
    fun setOnSkip(onSkip: () -> Unit) {
        this.onSkip = onSkip
    }

    /**
     * Start collecting NPS events and showing dialogs.
     * Call from Activity.onCreate() after setting callbacks.
     */
    fun attach(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NpsDisplayController.npsFlow.collect { config ->
                    showNps(activity, config)
                }
            }
        }
    }

    private fun showNps(activity: FragmentActivity, config: NpsConfig) {
        val submit = onSubmit ?: return
        val dialog = NpsDialogFragment.newInstance(config, submit, onSkip)
        dialog.show(activity.supportFragmentManager, "nps_dialog")
    }
}
