package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.response.StorySlideResponse
import android.content.Intent
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
