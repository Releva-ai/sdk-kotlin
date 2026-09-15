package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.response.StorySlideResponse
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Slide timer, lifecycle and tap-routing coverage for [StoryViewerActivity].
 *
 * Covers the fix in `startSlideTimer`: the slide advance is a posted [android.os.Handler]
 * callback on the real clock now, not a [android.animation.ValueAnimator] end listener. The
 * old form is exactly what an instrumented test — where animator durations collapse to zero
 * — could never have caught, since it made every slide advance take zero simulated time
 * too. Driving the advance from [org.robolectric.shadows.ShadowLooper] instead pins the
 * behaviour that made the old form invisible in this suite: one test idles less than the
 * slide's duration and expects no advance (would have failed under the old animator-driven
 * form), the other idles the slide's real duration and expects one.
 *
 * [StoryViewerActivity.disableProgressAnimatorForTest] is set for the duration of this suite:
 * a real `ValueAnimator` ticks via `Choreographer`, and under Robolectric's paused main
 * looper that has been observed to jump the fake clock forward by whole multiples of the
 * slide duration on the very next [shadowOf]-driven idle — even a bare, no-argument `idle()`,
 * which by contract only drains already-due messages. The animator only paints; disabling it
 * changes nothing about the Handler-driven advance these tests assert on.
 */
@RunWith(RobolectricTestRunner::class)
// StoryViewerActivity's own manifest entry already pins android:theme to
// Theme.AppCompat.NoActionBar (see the comment on that entry in AndroidManifest.xml) for
// exactly this reason — a host with no AppCompat ancestor theme would otherwise crash the
// first time this activity opens. Robolectric only sees it because
// testOptions.unitTests.isIncludeAndroidResources merges this module's manifest instead of
// falling back to bare AOSP resources; no per-test @Config override is needed.
@Config(sdk = [28])
class StoryViewerActivityTest {

    private lateinit var client: RelevaClient

    @Before
    fun setUp() {
        client = RelevaClient(RuntimeEnvironment.getApplication(), "", "test-token")
        StoryViewerActivity.disableProgressAnimatorForTest = true
    }

    @After
    fun tearDown() {
        StoryViewerActivity.disableProgressAnimatorForTest = false
    }

    private fun launchViewer(story: StoryResponse): StoryViewerActivity {
        // launch()'s context.startActivity needs a real host Activity, not the bare
        // Application context: without FLAG_ACTIVITY_NEW_TASK — which launch() does not
        // set, matching how StoryDisplayManager.pump() calls it with its host Activity —
        // Android (and Robolectric, faithfully) rejects starting an Activity from a
        // non-Activity context.
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        // The launched intent goes to Robolectric's shadow of `host`, not to a running
        // Activity — the key is what onCreate below actually needs, so a second,
        // explicitly built controller is what stands the viewer up under test control.
        val key = StoryViewerActivity.launch(context = host, story = story, client = client)
        val intent = Intent(host, StoryViewerActivity::class.java).apply {
            putExtra("releva_story_key", key)
        }
        return Robolectric.buildActivity(StoryViewerActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .get()
    }

    @Test
    fun `slide does not advance before its duration elapses`() {
        val story = StoryResponse(
            token = "timer-story",
            slides = listOf(
                StorySlideResponse(id = 1, durationSeconds = 5),
                StorySlideResponse(id = 2, durationSeconds = 5)
            )
        )
        val activity = launchViewer(story)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(0, activity.currentSlideIndexForTest())
    }

    @Test
    fun `slide advances once its duration has elapsed`() {
        val story = StoryResponse(
            token = "timer-story-2",
            slides = listOf(
                StorySlideResponse(id = 1, durationSeconds = 5),
                StorySlideResponse(id = 2, durationSeconds = 5)
            )
        )
        val activity = launchViewer(story)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))

        assertEquals(1, activity.currentSlideIndexForTest())
    }

    /**
     * Covers the `onPause`/`onResume` fix directly: [StoryViewerActivity.advanceDueAt] is
     * an absolute [android.os.SystemClock.uptimeMillis] deadline, so without capturing the
     * remainder in `onPause` the background interval gets subtracted from it wholesale and
     * the advance fires the instant the activity resumes — regardless of how much of the
     * slide's duration was actually left. Idling thirty simulated seconds while paused and
     * asserting the slide has *not* advanced is what a stale-deadline repost gets wrong;
     * idling the true remainder afterwards and asserting it advances exactly once is what a
     * remainder that was silently dropped (or double counted) would also get wrong.
     */
    @Test
    fun `slide timer does not skip ahead across a real background pause`() {
        val story = StoryResponse(
            token = "pause-story",
            slides = listOf(
                StorySlideResponse(id = 1, durationSeconds = 5),
                StorySlideResponse(id = 2, durationSeconds = 5)
            )
        )
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val key = StoryViewerActivity.launch(context = host, story = story, client = client)
        val intent = Intent(host, StoryViewerActivity::class.java).apply {
            putExtra("releva_story_key", key)
        }
        val controller = Robolectric.buildActivity(StoryViewerActivity::class.java, intent)
            .create()
            .start()
            .resume()

        // Two seconds into a five-second slide, then backgrounded for thirty — far longer
        // than the slide itself, and exactly the case the old absolute-deadline repost got
        // wrong.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        controller.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        controller.resume()

        assertEquals(0, controller.get().currentSlideIndexForTest())

        // The three seconds actually owed to the slide, plus margin.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertEquals(1, controller.get().currentSlideIndexForTest())
    }

    /**
     * Covers the `isAlive` window fix directly, at the unit rather than the
     * queue-integration level: [StoryViewerActivity.launch] must publish the key to
     * [StoryViewerActivity.isAlive] before `startActivity` returns, since
     * `StoryDisplayManager`'s host-resume observer can run and read it before this
     * activity's own `onCreate` ever executes (see the KDoc on `aliveKeys`).
     */
    @Test
    fun `isAlive is true immediately after launch, before onCreate runs`() {
        val story = StoryResponse(
            token = "isalive-story",
            slides = listOf(StorySlideResponse(id = 1, durationSeconds = 5))
        )
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

        val key = StoryViewerActivity.launch(context = host, story = story, client = client)

        assertTrue(StoryViewerActivity.isAlive(key))
    }

    /**
     * Covers the other half of the same fix: a back press must retire the key in
     * `finish()`, before `onDestroy` runs, not after. `onBackPressed()` — rather than
     * calling `close()` or the close button directly — is what actually broke this: it
     * drives `finish()` through [androidx.activity.OnBackPressedDispatcher]'s fallback
     * (`finishAfterTransition`), the same path a real back press takes, whereas the
     * host's `onResume` (which reads `isAlive`) fires before `onDestroy` completes. Under
     * Robolectric, `finishAfterTransition` reaches the actual `finish()` call (and so this
     * override) via a posted Runnable rather than synchronously, and that Runnable is not
     * yet due the instant `onBackPressed()` returns — a plain, no-argument
     * [org.robolectric.shadows.ShadowLooper.idle], which by contract only drains
     * already-due messages, leaves it queued and the key still alive.
     * [org.robolectric.shadows.ShadowLooper.runToEndOfTasks] drains it regardless of due
     * time, which is what actually observes `finish()` having run.
     */
    @Test
    fun `finishing retires the launch key immediately, before onDestroy runs`() {
        val story = StoryResponse(
            token = "finish-story",
            slides = listOf(StorySlideResponse(id = 1, durationSeconds = 5))
        )
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val key = StoryViewerActivity.launch(context = host, story = story, client = client)
        val intent = Intent(host, StoryViewerActivity::class.java).apply {
            putExtra("releva_story_key", key)
        }
        val controller = Robolectric.buildActivity(StoryViewerActivity::class.java, intent)
            .create()
            .start()
            .resume()

        controller.get().onBackPressed()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()

        assertFalse(StoryViewerActivity.isAlive(key))
    }

    /**
     * Device QA found the slide's call to action dead: a tap well inside the button's
     * reported bounds never reached its click listener, and the navigation overlay advanced
     * a slide instead. Everything the overlay covers is only reachable through the overlay's
     * own hit test — `findClickableViewAt`, fed from [MotionEvent.getRawX]/[MotionEvent.getRawY]
     * and compared against [View.getLocationOnScreen] — and on that device the test missed
     * the button. Why it missed was not established; it does not miss under Robolectric,
     * where the window sits at the display origin and window coordinates and screen
     * coordinates coincide, which is why [tap] is given a non-zero `rawOffset` here to force
     * the miss — one pixel past the button's own height, so the raw point falls outside its
     * bounds structurally rather than by a constant tuned to the current padding and text size.
     *
     * So what this pins is not the offset but the invariant the fix rests on: the action
     * button is a sibling above the overlay now, reached by ordinary touch dispatch, so a
     * tap on it runs the slide action whether or not the overlay's hit test can see it.
     * Without the fix the button sits under the overlay and this test advances a slide with
     * no action fired — verified by running it against the unfixed code.
     */
    @Test
    fun `tapping the action button runs the slide action even when the overlay hit test misses`() {
        val taps = mutableListOf<String>()
        val activity = launchVisibleViewer(
            story = StoryResponse(
                token = "action-story",
                slides = listOf(
                    StorySlideResponse(
                        id = 1,
                        durationSeconds = 30,
                        actionType = "link",
                        actionUrl = "https://example.com/offer",
                        actionLabel = "Open"
                    ),
                    StorySlideResponse(id = 2, durationSeconds = 30)
                )
            ),
            onLinkTap = { taps.add(it) }
        )
        val button = requireViewWithText(activity, "Open")

        // One pixel past the button's own height is guaranteed to clear half that height,
        // which is all offsetting both axes by the same amount needs to push the raw point
        // outside the button's bounds — structural, unlike a bare constant tied to today's
        // padding and text size.
        tap(activity, centreOf(button), rawOffset = button.height + 1f)

        assertEquals(listOf("https://example.com/offer"), taps)
        assertEquals(0, activity.currentSlideIndexForTest())
    }

    /**
     * The other half of the fix: the container the action button moved into spans the whole
     * slide, so a tap that misses the button must fall through it to the overlay underneath
     * and navigate as before. Right of centre advances a slide. No `rawOffset` here — this
     * is the ordinary case, and it passes before the fix as well as after; it is a guard
     * against the new container swallowing navigation, not a demonstration of the bug.
     *
     * The slide advance also lands on `actionContainer`'s obligation to clear itself: the
     * second slide has no `actionLabel`, so once the tap above lands there, no view with text
     * "Open" may remain in the tree — a leftover would sit above the overlay, permanently
     * covering that strip of every later slide and firing this slide's action for whichever
     * slide is current when it is eventually tapped.
     */
    @Test
    fun `tapping away from the action button still navigates`() {
        val taps = mutableListOf<String>()
        val activity = launchVisibleViewer(
            story = StoryResponse(
                token = "navigation-story",
                slides = listOf(
                    StorySlideResponse(
                        id = 1,
                        durationSeconds = 30,
                        actionType = "link",
                        actionUrl = "https://example.com/offer",
                        actionLabel = "Open"
                    ),
                    StorySlideResponse(id = 2, durationSeconds = 30)
                )
            ),
            onLinkTap = { taps.add(it) }
        )
        val buttonLocation = IntArray(2)
        requireViewWithText(activity, "Open").getLocationOnScreen(buttonLocation)

        // Right of centre, and halfway between the top of the screen and the button's top
        // edge — inside the container the button now lives in, but not on the button.
        tap(activity, (activity.window.decorView.width * 3 / 4f) to (buttonLocation[1] / 2f))

        assertEquals(emptyList<String>(), taps)
        assertEquals(1, activity.currentSlideIndexForTest())
        assertNull(findViewWithText(activity, "Open"))
    }

    /** The close button sits above the new container too, and must still close the viewer. */
    @Test
    fun `tapping the close button still closes the viewer`() {
        val activity = launchVisibleViewer(
            story = StoryResponse(
                token = "close-story",
                slides = listOf(
                    StorySlideResponse(
                        id = 1,
                        durationSeconds = 30,
                        actionType = "link",
                        actionUrl = "https://example.com/offer",
                        actionLabel = "Open"
                    )
                )
            )
        )

        tap(activity, centreOf(requireViewWithText(activity, "\u2715")))

        assertTrue(activity.isFinishing)
    }

    /**
     * As [launchViewer], but also drives the controller to `visible()` so the view hierarchy
     * is attached, measured and laid out — without that the views have no bounds and no touch
     * dispatch, by any path, can land on them.
     */
    private fun launchVisibleViewer(
        story: StoryResponse,
        onLinkTap: ((String) -> Unit)? = null
    ): StoryViewerActivity {
        val host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val key = StoryViewerActivity.launch(
            context = host, story = story, client = client, onLinkTap = onLinkTap
        )
        val intent = Intent(host, StoryViewerActivity::class.java).apply {
            putExtra("releva_story_key", key)
        }
        return Robolectric.buildActivity(StoryViewerActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .visible()
            .get()
    }

    private fun findViewWithText(activity: StoryViewerActivity, text: String): TextView? {
        fun find(view: View): TextView? {
            if (view is TextView && view.text?.toString() == text) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        return find(activity.window.decorView)
    }

    private fun requireViewWithText(activity: StoryViewerActivity, text: String): TextView =
        requireNotNull(findViewWithText(activity, text)) { "no view with text \"$text\"" }

    private fun centreOf(view: View): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
    }

    /**
     * Presses and releases at [point] through the activity's own touch dispatch.
     *
     * [rawOffset], when non-zero, leaves the event's raw (screen) coordinates that many
     * pixels away from its window coordinates — [MotionEvent.offsetLocation] moves the
     * window coordinates only, exactly as a parent does while dispatching. The window
     * coordinates still land on [point], so ordinary dispatch is unaffected; only a hit test
     * that reads the raw coordinates instead sees a different place.
     */
    private fun tap(
        activity: StoryViewerActivity,
        point: Pair<Float, Float>,
        rawOffset: Float = 0f
    ) {
        val (x, y) = point
        val downAt = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to downAt, MotionEvent.ACTION_UP to downAt + 10)) {
            val event = MotionEvent.obtain(downAt, at, action, x + rawOffset, y + rawOffset, 0)
            event.offsetLocation(-rawOffset, -rawOffset)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
