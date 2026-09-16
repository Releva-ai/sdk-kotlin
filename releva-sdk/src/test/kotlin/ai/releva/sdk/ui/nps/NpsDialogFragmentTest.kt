package ai.releva.sdk.ui.nps

import ai.releva.sdk.types.response.NpsAppearance
import ai.releva.sdk.types.response.NpsAppearanceDark
import ai.releva.sdk.types.response.NpsConfig
import ai.releva.sdk.types.response.NpsFollowUp
import ai.releva.sdk.types.response.NpsThankYou
import ai.releva.sdk.types.response.NpsTrigger
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
import org.junit.Assert.assertNull
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
 *
 * [NpsDisplayManager] is an `object` with no way to clear its callbacks, so the lambda [setUp]
 * registers on every run outlives this class inside the Robolectric sandbox; nothing outside
 * this suite reads it, so it is harmless, but a fresh `onSubmit` from a later test class would
 * silently replace it rather than stack with it.
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
     * `submitted` is written to [android.os.Bundle] but nothing else reads it back: every other
     * test in this class rotates before a score is ever submitted. A rotation on the thank-you
     * step is not exotic — the step sits on screen for two full seconds behind a
     * non-cancelable, non-dismissible sheet before it auto-dismisses — and if this ever stopped
     * surviving, the recreated dialog would fall through to the score step and ask an already
     * -answered user to answer again, producing a second submission with nothing on the wire to
     * collapse it. `thankYou` is set on the config so the assertion below names a string only
     * the thank-you step can produce, not the question or the follow-up.
     */
    @Test
    fun `a survey restored after a submission stays on the thank-you step`() {
        val thankYouMessage = "You made our day!"
        val (controller, original) = showSurvey(config(thankYou = NpsThankYou(promoter = thankYouMessage)))
        findViewWithText(original, "9")!!.performClick()
        findViewWithText(original, SUBMIT_LABEL)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(findViewWithText(original, thankYouMessage))

        val recreated = rotate(controller, original)

        assertNotNull(findViewWithText(recreated, thankYouMessage))
        assertNull(findViewWithText(recreated, "9"))
        assertEquals(1, submissions.size)
    }

    /**
     * The follow-up step has no skip button and the sheet is non-cancelable, so a user who
     * rotates with a required comment already typed and comes back to a disabled submit button
     * has no visible way forward. Asserting `isEnabled` is the point: the comment coming back
     * into the [EditText] would pass even if the restore ran before the [android.text.TextWatcher]
     * was wired, which is exactly the ordering bug this pins.
     */
    @Test
    fun `a comment restored into a required follow-up re-enables the submit button`() {
        val (controller, original) = showSurvey(config(followUpRequired = true))
        findViewWithText(original, "9")!!.performClick()
        editTextIn(original)!!.setText("quick delivery")

        val recreated = rotate(controller, original)

        assertEquals("quick delivery", editTextIn(recreated)?.text?.toString())
        assertTrue(findViewWithText(recreated, SUBMIT_LABEL)!!.isEnabled)
    }

    /**
     * The skip path moved to [NpsDisplayManager.skipCallback] along with submit, but nothing
     * else in this class reaches it: the default test config leaves `skipLabel` null, so the
     * button is never built. The mechanism is the same one the submit test already proves, so
     * this is mostly belt-and-braces coverage for the other half of the singleton read.
     */
    @Test
    fun `skipping after a configuration change still reaches the skip callback`() {
        var skipped = false
        NpsDisplayManager.setOnSkip { skipped = true }
        val (controller, original) = showSurvey(config(skipLabel = SKIP_LABEL))

        val recreated = rotate(controller, original)
        findViewWithText(recreated, SKIP_LABEL)!!.performClick()

        assertTrue(skipped)
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

    /**
     * The other tests in this class only prove the round trip for `followUp` (via
     * [PROMOTER_QUESTION], which nothing but `NpsFollowUp.fromMap` can produce) and, by
     * omission, that a *default* `appearance` survives — which a lost one is
     * indistinguishable from, since [NpsConfig.fromMap] substitutes
     * [ai.releva.sdk.types.response.NpsAppearance.defaults] right back in. `triggers` is a
     * `List<Map<String, Any?>>`, the one shape here that depends on `org.json`'s `wrap()`
     * recursing into collection *elements*, not just into maps — and it is the shape the
     * server actually sends, per `NpsManagerService.initialize`. This asserts on the
     * restored [NpsConfig] itself, via [NpsDialogFragment.configFromArgumentsForTest],
     * because neither field is rendered anywhere in the dialog's UI.
     */
    @Test
    fun `triggers and a non-default appearance survive a configuration change`() {
        val cfg = config(
            triggers = listOf(
                NpsTrigger(type = "customEvent", eventName = "checkout"),
                NpsTrigger(type = "sessionCount", minSessions = 3)
            ),
            appearance = NpsAppearance(
                primaryColor = "#112233",
                buttonStyle = "rounded",
                dark = NpsAppearanceDark(primaryColor = "#445566")
            )
        )
        val (controller, original) = showSurvey(cfg)

        val recreated = rotate(controller, original)

        val restored = recreated.configFromArgumentsForTest()
        assertEquals(cfg.triggers, restored?.triggers)
        assertEquals(cfg.appearance, restored?.appearance)
    }

    /**
     * [NpsDialogFragment.newInstance] with two callback arguments is deprecated but retained
     * for a caller who built the dialog directly instead of going through
     * [NpsDisplayManager.attach]; nothing else in this class calls it, so without this test
     * it ships unexercised. It also pins the semantic change the deprecation note describes:
     * the callbacks it takes are not scoped to this one dialog — they replace whatever is on
     * [NpsDisplayManager] process-wide, which is why [submissions], registered in [setUp],
     * receives nothing once this runs.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated three-argument newInstance still reaches its own callback across a configuration change`() {
        val deprecatedSubmissions = mutableListOf<Triple<String, Int, String?>>()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        NpsDialogFragment.newInstance(
            config(),
            onSubmit = { token, score, comment -> deprecatedSubmissions.add(Triple(token, score, comment)) }
        ).show(controller.get().supportFragmentManager, TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        val original = shownFragment(controller)
        findViewWithText(original, "9")!!.performClick()

        val recreated = rotate(controller, original)
        findViewWithText(recreated, SUBMIT_LABEL)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple(TOKEN, 9, null)), deprecatedSubmissions)
        assertTrue("the callback registered in setUp() should have been replaced process-wide", submissions.isEmpty())
    }

    private fun config(
        followUpRequired: Boolean = false,
        thankYou: NpsThankYou? = null,
        skipLabel: String? = null,
        triggers: List<NpsTrigger> = emptyList(),
        appearance: NpsAppearance = NpsAppearance.defaults()
    ) = NpsConfig(
        token = TOKEN,
        question = QUESTION,
        followUp = NpsFollowUp(promoter = PROMOTER_QUESTION, detractor = "What went wrong?"),
        followUpRequired = followUpRequired,
        submitLabel = SUBMIT_LABEL,
        skipLabel = skipLabel,
        thankYou = thankYou,
        triggers = triggers,
        appearance = appearance
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
        const val SKIP_LABEL = "No thanks"
    }
}
