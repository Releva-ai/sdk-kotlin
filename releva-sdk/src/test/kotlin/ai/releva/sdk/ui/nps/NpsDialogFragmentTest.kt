package ai.releva.sdk.ui.nps

import ai.releva.sdk.types.response.NpsConfig
import ai.releva.sdk.types.response.NpsFollowUp
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Configuration-change coverage for [NpsDialogFragment].
 *
 * Device QA found the survey disappearing the moment the phone was turned: the config and
 * the callbacks were set on the instance by `newInstance`, and the FragmentManager recreates
 * a fragment through its no-arg constructor, so the successor's `onCreateDialog` found a null
 * config and dismissed itself. Rotation is the easiest trigger; a dark-mode toggle, a font- or
 * display-size change, a locale change and a multi-window resize all take the same path.
 *
 * The submit test is the one that catches the half-fix: a dialog that comes back on screen but
 * whose callbacks did not come back with it is worse than one that vanished, because the user
 * taps submit, gets the thank-you screen and believes they have answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NpsDialogFragmentTest {

    private val submissions = mutableListOf<Triple<String, Int, String?>>()

    @Before
    fun setUp() {
        submissions.clear()
        // NpsDisplayManager is an object, so its callbacks outlive a single test; setting
        // them here is also what the fragment now depends on for every submission.
        NpsDisplayManager.setOnSubmit { token, score, comment ->
            submissions.add(Triple(token, score, comment))
        }
    }

    @Test
    fun `a survey survives a configuration change`() {
        val (controller, original) = showSurvey(config())
        assertNotNull(findViewWithText(original, QUESTION))

        val recreated = rotate(controller, original)

        assertTrue(recreated.isAdded)
        assertTrue(recreated.dialog?.isShowing == true)
        assertNotNull(findViewWithText(recreated, QUESTION))
    }

    /**
     * The score is not a view the platform can save for itself — the steps are built in code
     * without ids — and it is what decides which step the survey is on. Tapping 9 moves to the
     * promoter follow-up, which detractors never see, so finding that question after the
     * rotation is what says the score came back rather than the survey merely restarting.
     */
    @Test
    fun `a score selected before a configuration change is still selected after it`() {
        val (controller, original) = showSurvey(config())
        findViewWithText(original, "9")!!.performClick()
        assertNotNull(findViewWithText(original, PROMOTER_QUESTION))
        editTextIn(original)!!.setText("quick delivery")

        val recreated = rotate(controller, original)

        assertNotNull(findViewWithText(recreated, PROMOTER_QUESTION))
        assertEquals("quick delivery", editTextIn(recreated)?.text?.toString())
    }

    @Test
    fun `submitting after a configuration change still reaches the callback`() {
        val (controller, original) = showSurvey(config())
        findViewWithText(original, "9")!!.performClick()

        val recreated = rotate(controller, original)
        findViewWithText(recreated, SUBMIT_LABEL)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple(TOKEN, 9, null)), submissions)
    }

    /**
     * Carrying the config in the arguments must not turn the genuinely invalid case into a
     * dialog that stays open: a fragment with no arguments at all has no survey to show.
     */
    @Test
    fun `a fragment with no config still dismisses`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val fragment = NpsDialogFragment()

        fragment.show(controller.get().supportFragmentManager, TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(fragment.isAdded)
    }

    private fun config() = NpsConfig(
        token = TOKEN,
        question = QUESTION,
        followUp = NpsFollowUp(promoter = PROMOTER_QUESTION, detractor = "What went wrong?"),
        submitLabel = SUBMIT_LABEL
    )

    private fun showSurvey(
        config: NpsConfig
    ): Pair<ActivityController<FragmentActivity>, NpsDialogFragment> {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        NpsDialogFragment.newInstance(config).show(controller.get().supportFragmentManager, TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        return controller to shownFragment(controller)
    }

    /**
     * Rotates the host activity and returns the fragment that comes back. The [assertNotSame]
     * is why every caller's assertions mean anything: if the FragmentManager ever stopped
     * destroying and recreating the dialog here, the original instance would keep its own
     * fields and the tests would pass while testing nothing.
     */
    private fun rotate(
        controller: ActivityController<FragmentActivity>,
        original: NpsDialogFragment
    ): NpsDialogFragment {
        controller.configurationChange(
            Configuration(controller.get().resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
        )
        controller.get().supportFragmentManager.executePendingTransactions()
        val recreated = shownFragment(controller)
        assertNotSame(original, recreated)
        return recreated
    }

    private fun shownFragment(controller: ActivityController<FragmentActivity>): NpsDialogFragment {
        val fragment = controller.get().supportFragmentManager.findFragmentByTag(TAG)
        assertNotNull("no NPS dialog is showing", fragment)
        return fragment as NpsDialogFragment
    }

    private fun findViewWithText(fragment: NpsDialogFragment, text: String): TextView? {
        fun find(view: View): TextView? {
            if (view is TextView && view.text?.toString() == text) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return fragment.dialog?.window?.decorView?.let { find(it) }
    }

    private fun editTextIn(fragment: NpsDialogFragment): EditText? {
        fun find(view: View): EditText? {
            if (view is EditText) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return fragment.dialog?.window?.decorView?.let { find(it) }
    }

    private companion object {
        const val TAG = "nps_dialog"
        const val TOKEN = "rotation-survey"
        const val QUESTION = "How likely are you to recommend us?"
        const val PROMOTER_QUESTION = "What did you like most?"
        const val SUBMIT_LABEL = "Send feedback"
    }
}
