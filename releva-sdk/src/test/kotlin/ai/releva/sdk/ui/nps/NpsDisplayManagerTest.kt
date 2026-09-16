package ai.releva.sdk.ui.nps

import ai.releva.sdk.types.response.NpsConfig
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the stacking hazard [NpsDialogFragment]'s arguments-based restore
 * opens up. Before that fix, the only way a second [NpsDialogFragment] could exist was one the
 * FragmentManager was restoring after a configuration change, and a restored one dismissed
 * itself in `onCreateDialog` — so [NpsDisplayManager.showNps] could add a dialog unconditionally
 * and never actually stack one. Now a restored dialog stays on screen, and after process death
 * nothing remembers that: `NpsManagerService.suppressedThisSession` is in-memory only, so a
 * second [NpsDisplayManager.showNps] call for an activity that already has a survey showing —
 * e.g. the FragmentManager restores the dialog from its arguments and the next push response
 * still carries the same unanswered config, with a server-side trigger already fired — must not
 * add a second sheet on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NpsDisplayManagerTest {

    @Before
    fun setUp() {
        NpsDisplayManager.setOnSubmit { _, _, _ -> }
    }

    @Test
    fun `a second call to showNps does not stack a dialog on one already showing`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val config = NpsConfig(token = "first", question = "Rate us")

        NpsDisplayManager.showNps(activity, config)
        activity.supportFragmentManager.executePendingTransactions()
        NpsDisplayManager.showNps(activity, config.copy(token = "second"))
        activity.supportFragmentManager.executePendingTransactions()

        val dialogs = activity.supportFragmentManager.fragments.filterIsInstance<NpsDialogFragment>()
        assertEquals(1, dialogs.size)
    }

    @Test
    fun `showNps still shows when nothing is on screen yet`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val config = NpsConfig(token = "only", question = "Rate us")

        NpsDisplayManager.showNps(activity, config)
        activity.supportFragmentManager.executePendingTransactions()

        val dialogs = activity.supportFragmentManager.fragments.filterIsInstance<NpsDialogFragment>()
        assertEquals(1, dialogs.size)
    }
}
