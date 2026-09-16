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
 * Regression coverage for the stacking hazard the arguments-based restore opens up.
 * [NpsDisplayManager.showNps] used to add a dialog unconditionally, which never actually
 * stacked one: the only second [NpsDialogFragment] that could exist was one the FragmentManager
 * was restoring, and a restored one dismissed itself in `onCreateDialog`. Now it stays on
 * screen, so a second emission for an activity that already has a survey up — reachable in one
 * process, since `NpsManagerService.suppressedThisSession` is in-memory and `startNewSession()`
 * clears it — must not put a second non-cancelable sheet on top of it.
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
