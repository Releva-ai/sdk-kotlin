package ai.releva.sdk.ui.nps

import ai.releva.sdk.services.nps.NpsDisplayController
import ai.releva.sdk.types.response.NpsConfig
import androidx.annotation.VisibleForTesting
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

    private const val DIALOG_TAG = "nps_dialog"

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
     * The callbacks [NpsDialogFragment] invokes. It reads them from here when it needs
     * them rather than being handed them at construction: a lambda cannot travel in the
     * arguments Bundle that carries the fragment through a configuration change, so a
     * dialog recreated by one would hold nulls and its submit button would silently do
     * nothing.
     */
    internal fun submitCallback(): (suspend (String, Int, String?) -> Unit)? = onSubmit

    internal fun skipCallback(): (() -> Unit)? = onSkip

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

    /**
     * `internal` rather than `private` so a test can call it directly without driving the
     * full `attach()` -> `repeatOnLifecycle` -> flow-collection chain.
     *
     * The tag check guards a stacking hazard this fix's own restore behaviour opens up.
     * Before it, the only way a second [NpsDialogFragment] could exist was one the
     * FragmentManager was restoring, and a restored one dismissed itself in
     * `onCreateDialog` — so this could add unconditionally. Now a restored dialog stays on
     * screen, and after process death nothing remembers that: `NpsManagerService`'s
     * `suppressedThisSession` is in-memory only, so if the next push response still carries
     * the same unanswered `nps` config and a server-side trigger has already fired, this
     * would otherwise show a second sheet on top of the one the FragmentManager already
     * restored.
     */
    @VisibleForTesting
    internal fun showNps(activity: FragmentActivity, config: NpsConfig) {
        if (onSubmit == null) return
        NpsDisplayController.consumeNps()
        if (activity.supportFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        val dialog = NpsDialogFragment.newInstance(config)
        dialog.show(activity.supportFragmentManager, DIALOG_TAG)
    }
}
