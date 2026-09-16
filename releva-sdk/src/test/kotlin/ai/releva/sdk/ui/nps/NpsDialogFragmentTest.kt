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
import kotlinx.coroutines.CompletableDeferred
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
     * server actually sends, per `NpsManagerService.initialize`. This asserts the restored
     * [NpsConfig] equals the original outright — a strictly stronger check than comparing
     * `triggers` and `appearance` individually, since [NpsConfig] is a data class and this
     * also covers every other field the same generic `org.json` path carries, via
     * [NpsDialogFragment.configFromArguments] because none of it is rendered in the dialog's
     * UI.
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

        assertEquals(cfg, recreated.configFromArguments())
    }

    /**
     * The third restore arm: a score came back but there is no follow-up question for it and
     * nothing was submitted. A score with no follow-up submits the moment it is tapped, so the
     * only way to reach this state is a recreation that interrupted the submission —
     * `onDestroyView` cancels the fragment's scope, so the in-flight call dies with it.
     *
     * Landing on the score step means the user can produce a second response for a token the
     * server may already have accepted. That is the deliberate trade: the alternative, treating
     * this as submitted and showing the thank-you step, tells a user their feedback was sent
     * when the cancellation means it may not have been — the failure the brief singles out as
     * the worse of the two. Pinning it here stops a later change flipping it silently.
     */
    @Test
    fun `a score whose submission was interrupted comes back to the score step`() {
        val thankYouMessage = "You made our day!"
        val (controller, original) = showSurvey(
            config(followUp = null, thankYou = NpsThankYou(promoter = thankYouMessage))
        )
        findViewWithText(original, "9")!!.performClick()
        // No idle() on purpose: submitScore dispatches through Dispatchers.Main, so under
        // Robolectric's paused looper the submission is still queued when the rotation lands,
        // and onDestroyView's scope.cancel() takes it with the fragment.

        val recreated = rotate(controller, original)

        assertNotNull(findViewWithText(recreated, QUESTION))
        assertNotNull(findViewWithText(recreated, "9"))
        // The assertion that separates the two outcomes: not the thank-you step, so the user
        // is not told a cancelled submission was sent.
        assertNull(findViewWithText(recreated, thankYouMessage))
        assertTrue(submissions.isEmpty())
    }

    /**
     * The other interrupted-submission test above rotates before `onSubmit` has been
     * dispatched at all, so `submitScore`'s `try` is never entered. On a device the more
     * likely window is a rotation while the coroutine is genuinely suspended *inside*
     * `onSubmit` — e.g. waiting on network IO — which is what this pins: `onDestroyView`'s
     * `scope.cancel()` resumes that suspension with a `CancellationException`, and if that
     * were swallowed by the generic `catch` instead of rethrown, execution would fall through
     * to `showThankYouStep` on a fragment already mid-detach, whose `requireContext()` throws
     * `IllegalStateException` — an app crash. `idle()` after tapping submit lets the body
     * start and park inside the callback before rotating; `idle()` again after rotating is
     * what actually runs the posted cancellation resumption, since `Dispatchers.Main` here is
     * a paused Robolectric looper.
     *
     * The crash this pins does not surface as a thrown exception `idle()` propagates:
     * kotlinx.coroutines hands an exception that escapes a coroutine with no
     * `CoroutineExceptionHandler` straight to `Thread.currentThread().uncaughtExceptionHandler`
     * — a direct call, not a throw — which is also exactly why it is a real app crash rather
     * than something a `try`/`catch` around `idle()` could ever have caught. A default handler
     * is installed for the duration of the test to catch that call and turn it back into a
     * assertion failure.
     */
    @Test
    fun `a submission cancelled while suspended in the callback does not crash the recreated dialog`() {
        val uncaught = mutableListOf<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val gate = CompletableDeferred<Unit>()
            NpsDisplayManager.setOnSubmit { _, _, _ -> gate.await() }
            val (controller, original) = showSurvey(config(followUp = null))
            findViewWithText(original, "9")!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val recreated = rotate(controller, original)
            shadowOf(Looper.getMainLooper()).idle()

            assertNotNull(findViewWithText(recreated, QUESTION))
            assertNotNull(findViewWithText(recreated, "9"))
            assertTrue(submissions.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertTrue("submitScore must not crash the main thread when cancelled mid-callback, got: $uncaught", uncaught.isEmpty())
    }

    /**
     * [NpsDialogFragment.newInstance] with two callback arguments is deprecated but retained
     * for a caller who built the dialog directly instead of going through
     * [NpsDisplayManager.attach]; nothing else in this class calls it, so without this test
     * it ships unexercised. It also pins the scoping the deprecation note describes: the
     * callback it takes is kept on the returned instance only, so it does not replace
     * whatever [NpsDisplayManager] holds — [submissions], registered in [setUp], stays empty
     * because this survey never reaches [NpsDisplayManager]'s callback at all while the
     * original instance is still alive.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated three-argument newInstance uses its own callback and does not touch NpsDisplayManager`() {
        val deprecatedSubmissions = mutableListOf<Triple<String, Int, String?>>()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        NpsDialogFragment.newInstance(
            config(followUp = null),
            onSubmit = { token, score, comment -> deprecatedSubmissions.add(Triple(token, score, comment)) }
        ).show(controller.get().supportFragmentManager, TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        val original = shownFragment(controller)
        findViewWithText(original, "9")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple(TOKEN, 9, null)), deprecatedSubmissions)
        assertTrue("NpsDisplayManager's callback, registered in setUp(), must not fire for a scoped call", submissions.isEmpty())
    }

    /**
     * The trade the deprecation note names: an instance field cannot survive a configuration
     * change, so a dialog built through the deprecated overload and then recreated falls back
     * to [NpsDisplayManager]'s callback rather than the one this call was given — worse than
     * the callback surviving, but confined to the path the deprecation is steering callers off
     * of, and not worse than master, where the whole dialog was dismissed on recreation.
     */
    @Suppress("DEPRECATION")
    @Test
    fun `the deprecated three-argument newInstance falls back to NpsDisplayManager after a configuration change`() {
        val deprecatedSubmissions = mutableListOf<Triple<String, Int, String?>>()
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        NpsDialogFragment.newInstance(
            config(followUp = null),
            onSubmit = { token, score, comment -> deprecatedSubmissions.add(Triple(token, score, comment)) }
        ).show(controller.get().supportFragmentManager, TAG)
        controller.get().supportFragmentManager.executePendingTransactions()
        val original = shownFragment(controller)

        val recreated = rotate(controller, original)
        findViewWithText(recreated, "9")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the deprecated call's own callback cannot survive recreation", deprecatedSubmissions.isEmpty())
        assertEquals(listOf(Triple(TOKEN, 9, null)), submissions)
    }

    private fun config(
        followUp: NpsFollowUp? = NpsFollowUp(promoter = PROMOTER_QUESTION, detractor = "What went wrong?"),
        followUpRequired: Boolean = false,
        thankYou: NpsThankYou? = null,
        skipLabel: String? = null,
        triggers: List<NpsTrigger> = emptyList(),
        appearance: NpsAppearance = NpsAppearance.defaults()
    ) = NpsConfig(
        token = TOKEN,
        question = QUESTION,
        followUp = followUp,
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
