package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.response.StorySlideResponse
import ai.releva.sdk.ui.banner.DesignRenderer
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full-screen story viewer similar to Instagram/Facebook stories.
 * Displays slides with progress bars, auto-advance, tap navigation, and close button.
 */
class StoryViewerActivity : AppCompatActivity() {

    private var currentSlideIndex = 0
    private var storyCompleteTracked = false
    private var progressAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Pending advance to the next slide. Cancelled wherever the progress animator is. */
    private var advanceRunnable: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * [SystemClock.uptimeMillis] at which the pending [advanceRunnable] is due, or 0L if
     * none is scheduled. The timer's own stamp rather than the animator's: reading the
     * animator back (`currentPlayTime`, `isPaused`) is exactly the coupling this fix
     * removes, and `Animator.pause()` is a no-op once the animation has ended — which, at
     * animator duration scale 0 (animations disabled — the setting fix #4 is about), it
     * always has been by the time [onPause] runs. Because this is an absolute deadline it
     * keeps counting down while backgrounded, so [onPause] converts it into
     * [advanceRemainingMs] before anything reads it again; [onResume] reposts from that
     * remainder rather than from this stamp directly.
     */
    private var advanceDueAt: Long = 0L
    /**
     * Milliseconds still owed to the current slide as of the last [onPause], or 0L while
     * the timer is running (or nothing is scheduled). [advanceDueAt] is an absolute
     * deadline, so it keeps counting down while the app is backgrounded; reposting from it
     * directly in [onResume] would subtract the whole background interval from the
     * remainder, firing the advance immediately after almost any real pause. Capturing the
     * remainder here and reposting *that* in [onResume] is what actually stops the clock.
     */
    private var advanceRemainingMs: Long = 0L
    /** This activity's own launch key, published via [aliveKeys] for [isAlive]. */
    private var launchKey: String? = null

    private lateinit var contentContainer: FrameLayout
    /**
     * Holds the current slide's action button, as a sibling *above* the navigation overlay
     * instead of a child of [contentContainer] underneath it. See [setupUI].
     */
    private lateinit var actionContainer: FrameLayout
    private lateinit var progressContainer: LinearLayout
    private val progressBars = mutableListOf<View>()
    private val progressFills = mutableListOf<View>()

    companion object {
        private const val TAG = "StoryViewer"
        /** Tag value set on views that need touch forwarding (e.g. carousel). */
        const val INTERACTIVE_VIEW_TAG = "releva_interactive"
        private const val EXTRA_LAUNCH_KEY = "releva_story_key"
        private const val STATE_SLIDE_INDEX = "releva_story_slide_index"
        private const val STATE_STORY_COMPLETE_TRACKED = "releva_story_complete_tracked"
        private const val STATE_ADVANCE_REMAINING_MS = "releva_story_advance_remaining_ms"

        // The launch data for every viewer whose launch is still running. Keyed by launch
        // key and held until the launch is retired, not consumed by the first onCreate: a
        // configuration change destroys the activity and recreates it from the *same*
        // intent, so a second onCreate arrives with the same key and has to find the entry
        // still there. Consuming it on first read is what made a story vanish on rotation.
        private val pendingLaunches = java.util.concurrent.ConcurrentHashMap<String, PendingLaunchData>()

        // Keys for viewers currently alive, so a caller holding a launch key can ask "is
        // this viewer still on screen (or on its way to it)?" without relying on a boolean
        // some other call site is responsible for clearing. Backed by a thread-safe set
        // since StoryDisplayManager.pump() and lifecycle callbacks can both touch it.
        //
        // Liveness spans launch-requested to gone, not created-to-destroyed: the key is
        // added in launch(), before startActivity, and removed the moment finish() is
        // called — not in onDestroy. Both ends of that matter for StoryDisplayManager's
        // host-onResume observer, which runs synchronously inside the host's own onResume:
        //   - Added at onCreate rather than launch() left a window between launch() and
        //     onCreate where isAlive was false for a viewer already on its way to the
        //     screen; a host resume in that window (e.g. a fast double-resume) cleared the
        //     active-viewer key and pump() started a second viewer over the first.
        //   - Removed at onDestroy rather than finish() missed the back-press path
        //     entirely: the host's onResume runs after the finishing viewer's onPause but
        //     before its onStop/onDestroy (documented Activity lifecycle ordering), so
        //     isAlive(key) was still true at the exact moment the observer checked it, the
        //     active-viewer key was never cleared, and the rest of the queue stalled behind
        //     a viewer that was already gone.
        private val aliveKeys = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )

        /** True if the viewer launched with this key has been requested and is not finishing. */
        fun isAlive(key: String): Boolean = aliveKeys.contains(key)

        /** Ends a launch: the key stops being alive and its data stops being restorable. */
        private fun retireLaunch(key: String) {
            aliveKeys.remove(key)
            pendingLaunches.remove(key)
        }

        /**
         * Skips creating the decorative progress-fill [ValueAnimator] in [startSlideTimer]
         * when true. Test-only: a real `ValueAnimator` posts its ticks through
         * `Choreographer`, and under Robolectric's paused main looper that interacts badly
         * with the fake clock — even a bare, no-argument
         * [org.robolectric.shadows.ShadowLooper.idle], which by contract only drains
         * already-due messages, has been observed to jump the clock forward by whole
         * multiples of the slide duration and fire the real advance early. The animator
         * only paints; disabling it changes nothing about the behaviour this class's tests
         * assert on (the Handler-driven advance), so it is the animator that yields here
         * rather than the test contorting itself around a Robolectric/Choreographer quirk.
         */
        @VisibleForTesting
        internal var disableProgressAnimatorForTest = false

        private data class PendingLaunchData(
            val story: StoryResponse,
            val client: RelevaClient,
            val onLinkTap: ((String) -> Unit)?,
            val onClose: (() -> Unit)?
        )

        /** Launches the viewer and returns its launch key, for use with [isAlive]. */
        fun launch(
            context: Context,
            story: StoryResponse,
            client: RelevaClient,
            onLinkTap: ((String) -> Unit)? = null,
            onClose: (() -> Unit)? = null
        ): String {
            val key = java.util.UUID.randomUUID().toString()
            pendingLaunches[key] = PendingLaunchData(story, client, onLinkTap, onClose)
            // Published before startActivity, not in onCreate: see the note on aliveKeys.
            aliveKeys.add(key)
            val intent = Intent(context, StoryViewerActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_KEY, key)
            }
            context.startActivity(intent)
            return key
        }
    }

    private lateinit var story: StoryResponse
    private var client: RelevaClient? = null
    private var onLinkTap: ((String) -> Unit)? = null
    private var onCloseCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(EXTRA_LAUNCH_KEY) ?: run { finish(); return }
        // launchKey is set before either early-exit call to finish() below: launch() added
        // this key to aliveKeys before startActivity, and finish() (overridden below) is
        // what retires it, so an instance that aborts before reaching setupUI() still has
        // to clear its own key rather than leaving it alive forever.
        launchKey = key
        // Read, not removed: the entry belongs to the launch, not to this instance, and a
        // recreated instance arrives here with the same key. finish() retires it.
        val data = pendingLaunches[key] ?: run { finish(); return }

        story = data.story
        client = data.client
        onLinkTap = data.onLinkTap
        onCloseCallback = data.onClose

        if (story.slides.isEmpty()) {
            finish()
            return
        }

        savedInstanceState?.let {
            currentSlideIndex = it.getInt(STATE_SLIDE_INDEX)
            storyCompleteTracked = it.getBoolean(STATE_STORY_COMPLETE_TRACKED)
            // Reposted by onResume, which runs right after this — see onSaveInstanceState.
            advanceRemainingMs = it.getLong(STATE_ADVANCE_REMAINING_MS)
        }

        setupUI()
        // Only for a genuinely new viewer. A recreated one is the same impression of the
        // same story: tracking it again would count one rotation as a second view.
        if (savedInstanceState == null) {
            trackEvent("storyImpression")
            trackSlideView()
        }
        // A restored instance leaves its advance to onResume; see scheduleAdvanceNow.
        startSlideTimer(scheduleAdvanceNow = savedInstanceState == null)
    }

    /**
     * Saves what a recreated instance cannot get from the launch data: which slide is
     * showing, whether storyComplete has already been tracked, and how much of the current
     * slide was left. [advanceRemainingMs] is captured by [onPause], which always runs
     * before this, and the restored value is reposted by [onResume] — the same machinery
     * that already survives a background pause, so a rotation costs the slide only the time
     * the recreation itself takes rather than restarting it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SLIDE_INDEX, currentSlideIndex)
        outState.putBoolean(STATE_STORY_COMPLETE_TRACKED, storyCompleteTracked)
        outState.putLong(STATE_ADVANCE_REMAINING_MS, advanceRemainingMs)
    }

    /**
     * The slide the timer will advance from next, so a test can assert the advance happens
     * on the content's own clock rather than an animator whose duration the system can
     * scale to zero. Avoids reflection on the private [currentSlideIndex] field, the same
     * reasoning as [ai.releva.sdk.services.session.SessionService.setLastPauseMs].
     */
    @VisibleForTesting
    internal fun currentSlideIndexForTest(): Int = currentSlideIndex

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun setupUI() {
        // Slides are addressed through currentSlideIndex, not slide 0: after a
        // configuration change this runs again on the slide the viewer was already showing.
        val root = FrameLayout(this).apply {
            setBackgroundColor(getSlideBackgroundColor(story.slides[currentSlideIndex]))
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Content area below progress bars. actionContainer, added further down, must keep
        // identical layout params to this — see the comment there.
        contentContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(40) }
        }
        root.addView(contentContainer)

        // Navigation overlay on top — intercepts taps for left/right slide navigation,
        // but forwards taps to clickable content views (buttons, links) underneath.
        val activity = this
        val touchOverlay = object : FrameLayout(activity) {
            private var forwardToContent = false

            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                    // Check if a clickable view in the content layer would handle this tap
                    forwardToContent = findClickableViewAt(contentContainer, ev.rawX.toInt(), ev.rawY.toInt())
                }

                if (forwardToContent) {
                    // Forward the entire gesture to the content container
                    return contentContainer.dispatchTouchEvent(ev)
                }

                // Handle as slide navigation
                return super.dispatchTouchEvent(ev)
            }

            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val halfWidth = width / 2
                    if (event.x < halfWidth) {
                        goToPreviousSlide()
                    } else {
                        goToNextSlide()
                    }
                    return true
                }
                return true
            }
        }.apply {
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(40) }
        }
        root.addView(touchOverlay)

        // The action button lives here, not in contentContainer, because anything under the
        // overlay is reachable only through findClickableViewAt — which compares the event's
        // raw coordinates against the view's screen coordinates — and on a device that
        // comparison missed the button: a tap well inside its reported bounds navigated
        // instead. Above the overlay the button is reached by ordinary dispatch, the path
        // topRow's close button already takes and the one that works on that device.
        // Nothing in here is clickable but the button, so a tap that misses it is not
        // consumed and still reaches the overlay as navigation; the layout params below
        // must stay identical to contentContainer's, which is what keeps the button in the
        // place it renders today.
        actionContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(40) }
        }
        root.addView(actionContainer)

        // Progress bar row + close button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val activeColor = parseIndicatorColor(story.progressIndicatorColor)
        val inactiveColor = parseIndicatorColor(story.progressIndicatorInactiveColor)

        for (i in story.slides.indices) {
            if (i > 0) {
                progressContainer.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(4), 0)
                })
            }

            val barContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f)
                background = GradientDrawable().apply {
                    setColor(inactiveColor)
                    cornerRadius = dp(2).toFloat()
                }
            }

            val fill = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                background = GradientDrawable().apply {
                    setColor(activeColor)
                    cornerRadius = dp(2).toFloat()
                }
            }
            barContainer.addView(fill)

            progressContainer.addView(barContainer)
            progressBars.add(barContainer)
            progressFills.add(fill)
        }

        topRow.addView(progressContainer)

        // Close button
        topRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })

        val closeBtn = TextView(this).apply {
            text = "\u2715"
            setTextColor(activeColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { close() }
        }
        topRow.addView(closeBtn)

        root.addView(topRow)

        // Action button area
        renderSlideContent(story.slides[currentSlideIndex])

        setContentView(root)
    }

    /**
     * Advance to the next slide after this one's duration, filling its progress bar as it
     * goes.
     *
     * Two separate concerns, and they used to be one. The progress bar is decoration: a
     * ValueAnimator is exactly right for it, and a user who has turned animations off is
     * entitled to have it snap. How long the slide *stays* is not decoration — it is the
     * content's own pacing, the difference between reading a story and watching it flash
     * past.
     *
     * Driving both from the animator meant that with animations disabled — an accessibility
     * setting, a battery saver, a developer option, or a test environment — every slide
     * ended the moment it began and the whole story played instantly. The animator's
     * duration is scaled to zero by the system, its end listener fires immediately, and
     * that listener was what advanced the slide. It also made tapping back look broken:
     * the previous slide appeared and was skipped forward again before anything could be
     * seen.
     *
     * So the advance is a posted callback on a real clock, and the animator now only
     * paints.
     *
     * @param scheduleAdvanceNow Whether to post the advance immediately. False on a restored
     * instance's [onCreate], because such an instance may never reach [onResume]: the platform
     * can relaunch a non-resumed activity straight through `onCreate -> onStart -> onStop`,
     * e.g. when the host was already backgrounded as the configuration change arrived. Such an
     * instance is stopped, not destroyed, so neither of the two places that cancel a posted
     * advance reaches it — [onPause] never runs, and [onDestroy] has not yet. Posting here
     * would leave a full-duration advance to fire unattended against an invisible viewer:
     * tracking slide views nobody saw, and on the default end behaviour closing the story.
     * [onResume] reposts [advanceRemainingMs] whenever it does run, including on the ordinary
     * visible rotation, so it is the one place that may start a restored slide's clock. A
     * genuinely new viewer has no such gap: `launch` starts it to the foreground directly.
     * The progress bars and the decorative animator are unaffected either way.
     */
    private fun startSlideTimer(scheduleAdvanceNow: Boolean = true) {
        progressAnimator?.cancel()
        advanceRunnable?.let { handler.removeCallbacks(it) }

        val slide = story.slides[currentSlideIndex]
        // durationSeconds comes off the wire. 0 combined with endBehavior "loop" is an
        // unbounded main-thread re-render loop, and a negative value throws out of
        // ValueAnimator.setDuration; clamp to a sane range instead of trusting either.
        val duration = slide.durationSeconds.coerceIn(1, 60) * 1000L

        // Update progress fills for completed/current slides
        updateProgressBars()

        if (!disableProgressAnimatorForTest) {
            progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    val fill = progressFills[currentSlideIndex]
                    val parent = fill.parent as? View ?: return@addUpdateListener
                    fill.layoutParams = FrameLayout.LayoutParams(
                        (parent.width * progress).toInt().coerceAtLeast(0),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                start()
            }
        }

        if (scheduleAdvanceNow) {
            scheduleAdvance(duration)
        }
    }

    private fun scheduleAdvance(delayMs: Long) {
        advanceRunnable?.let { handler.removeCallbacks(it) }
        advanceDueAt = SystemClock.uptimeMillis() + delayMs
        val advance = Runnable { goToNextSlide() }
        advanceRunnable = advance
        handler.postDelayed(advance, delayMs)
    }

    /**
     * Backgrounding the app used to leave the real-clock advance running, so the user came
     * back to a finished (or looped-past) story with storySlideView/storyComplete already
     * tracked for content nobody saw. Cancelling the pending advance stops further
     * *advances*, but [advanceDueAt] is an absolute [SystemClock.uptimeMillis] deadline
     * that keeps counting down regardless — reposting from it directly in [onResume] would
     * subtract the whole background interval from the remainder, so a slide backgrounded
     * with time left is gone the instant the app comes back. Converting it into
     * [advanceRemainingMs] here, and reposting that fixed amount in [onResume] instead of
     * recomputing from the stale deadline, is what actually stops the clock.
     *
     * The animator is paused too, but only because it paints — pausing it is not what
     * stops the timer, and [onResume] does not ask it anything before deciding whether to
     * repost. `Animator.pause()` is a no-op once the animation has ended, which at animator
     * duration scale 0 (animations disabled) it always has by the time this runs; reading
     * `isPaused` back to gate the repost is exactly the bug this fixes.
     */
    override fun onPause() {
        super.onPause()
        progressAnimator?.pause()
        advanceRunnable?.let { handler.removeCallbacks(it) }
        advanceRemainingMs =
            if (advanceDueAt > 0L) (advanceDueAt - SystemClock.uptimeMillis()).coerceAtLeast(0L) else 0L
    }

    override fun onResume() {
        super.onResume()
        progressAnimator?.takeIf { it.isPaused }?.resume()
        if (advanceRemainingMs > 0L) {
            scheduleAdvance(advanceRemainingMs)
            advanceRemainingMs = 0L
        }
    }

    private fun updateProgressBars() {
        for (i in story.slides.indices) {
            val fill = progressFills[i]
            val parent = fill.parent as? View ?: continue
            when {
                i < currentSlideIndex -> {
                    // Completed
                    fill.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                i > currentSlideIndex -> {
                    // Not yet reached
                    fill.layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                // Current slide is handled by the animator
            }
        }
    }

    private fun goToNextSlide() {
        progressAnimator?.cancel()
        advanceRunnable?.let { handler.removeCallbacks(it) }
        // Cleared here and reset by the next scheduleAdvance() call (via startSlideTimer())
        // on whichever branch below restarts the timer. The "stayOnLast" branch does not,
        // so it must clear this itself — otherwise a stale due-time left over from the
        // slide that just ended would make the next onPause/onResume cycle fire an advance
        // immediately, despite the timer having no runnable pending.
        advanceDueAt = 0L

        if (currentSlideIndex < story.slides.size - 1) {
            currentSlideIndex++
            trackSlideView()
            renderSlideContent(story.slides[currentSlideIndex])
            startSlideTimer()
        } else {
            // End of story
            if (!storyCompleteTracked) {
                storyCompleteTracked = true
                trackEvent("storyComplete")
            }

            when (story.endBehavior) {
                "loop" -> {
                    currentSlideIndex = 0
                    trackSlideView()
                    renderSlideContent(story.slides[0])
                    startSlideTimer()
                }
                "stayOnLast" -> {
                    // Fill last progress bar
                    val fill = progressFills[currentSlideIndex]
                    fill.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                else -> close() // dismiss
            }
        }
    }

    private fun goToPreviousSlide() {
        progressAnimator?.cancel()
        advanceRunnable?.let { handler.removeCallbacks(it) }

        if (currentSlideIndex > 0) {
            currentSlideIndex--
            trackSlideView()
            renderSlideContent(story.slides[currentSlideIndex])
            startSlideTimer()
        } else {
            // Already on first slide, restart it
            startSlideTimer()
        }
    }

    private fun close() {
        trackEvent("storyClose")
        onCloseCallback?.invoke()
        finish()
    }

    private fun renderSlideContent(slide: StorySlideResponse) {
        contentContainer.removeAllViews()
        actionContainer.removeAllViews()

        // Update background color
        (contentContainer.parent as? View)?.setBackgroundColor(getSlideBackgroundColor(slide))

        if (slide.design != null) {
            val scrollView = ScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val designView = DesignRenderer.render(this, slide.design!!) { url ->
                // Track click
                scope.launch {
                    try {
                        client?.storyAction(story, action = "storySlideClick", slideId = slide.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error tracking slide click", e)
                    }
                }
                onLinkTap?.invoke(url)
            }
            scrollView.addView(designView)
            contentContainer.addView(scrollView)
        }

        // Action button
        if (slide.actionType != null && slide.actionType != "none" &&
            !slide.actionLabel.isNullOrEmpty()) {

            val actionBtn = TextView(this).apply {
                text = slide.actionLabel
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(14), dp(24), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(8).toFloat()
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM
                    setMargins(dp(24), 0, dp(24), dp(24))
                }
                setOnClickListener { handleSlideAction(slide) }
            }
            actionContainer.addView(actionBtn)
        }
    }

    private fun handleSlideAction(slide: StorySlideResponse) {
        if (slide.actionType == null || slide.actionType == "none") return

        // Track click
        scope.launch {
            try {
                client?.storyAction(story, action = "storySlideClick", slideId = slide.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking slide click", e)
            }
        }

        if (!slide.actionUrl.isNullOrEmpty()) {
            onLinkTap?.invoke(slide.actionUrl)
            if (slide.actionType == "dismiss") {
                close()
            }
        }
    }

    private fun trackEvent(action: String) {
        scope.launch {
            try {
                client?.storyAction(story, action = action)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking $action", e)
            }
        }
    }

    private fun trackSlideView() {
        val slide = story.slides[currentSlideIndex]
        scope.launch {
            try {
                client?.storyAction(story, action = "storySlideView", slideId = slide.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking slide view", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getSlideBackgroundColor(slide: StorySlideResponse): Int {
        if (slide.design == null) return Color.BLACK
        val body = slide.design["body"] as? Map<String, Any?> ?: return Color.BLACK
        val values = body["values"] as? Map<String, Any?> ?: return Color.BLACK
        val bgColor = values["backgroundColor"] as? String ?: return Color.BLACK
        return DesignRenderer.parseColor(bgColor) ?: Color.BLACK
    }

    private fun parseIndicatorColor(colorStr: String): Int {
        return DesignRenderer.parseColor(colorStr) ?: Color.WHITE
    }

    /**
     * Recursively check if there's an interactive view at the given screen coordinates.
     * Detects views with click listeners, touch listeners (tagged with INTERACTIVE_TAG),
     * or that are natively clickable (buttons, etc).
     */
    private fun findClickableViewAt(parent: View, x: Int, y: Int): Boolean {
        if (parent.visibility != View.VISIBLE) return false

        val location = IntArray(2)
        parent.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + parent.width
        val bottom = top + parent.height

        if (x < left || x > right || y < top || y > bottom) return false

        if (parent is ViewGroup) {
            for (i in parent.childCount - 1 downTo 0) {
                if (findClickableViewAt(parent.getChildAt(i), x, y)) return true
            }
        }

        // Check for click listeners, or views tagged as interactive (e.g. carousel ViewFlipper with touch listener)
        return parent.hasOnClickListeners() || parent.tag == INTERACTIVE_VIEW_TAG
    }

    /**
     * Retires [launchKey] from [aliveKeys] the moment finishing is requested, not in
     * [onDestroy]. `finish()` runs synchronously and marks [android.app.Activity.isFinishing]
     * before any of `onPause`/`onStop`/`onDestroy` are dispatched, so this is the earliest
     * point at which `isAlive(key)` can honestly become false — and on the back-press path,
     * it is the only point that runs before the host's `onResume`: the host resumes between
     * this viewer's `onPause` and its `onStop`/`onDestroy` (documented Activity lifecycle
     * ordering), so clearing the key in `onDestroy` was always too late for the one caller
     * — `StoryDisplayManager`'s host-resume observer — this exists for.
     *
     * This is also where the launch data is dropped, since finishing is the one thing that
     * means no further instance will need it.
     */
    override fun finish() {
        launchKey?.let { retireLaunch(it) }
        super.finish()
    }

    override fun onDestroy() {
        progressAnimator?.cancel()
        advanceRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        // Safety net: finish() is the normal retirement point (see its KDoc), but a viewer
        // that is destroyed without finish() ever being called (e.g. the host process/task
        // is torn down directly) must not leave a stale key alive forever. Both removals
        // are idempotent, so this is a no-op on the normal finish()-then-destroy path.
        //
        // A configuration change is the one destroy that is not the end of the viewer: the
        // successor instance is created from the same intent and needs both the key (it is
        // still on screen, so isAlive must stay true across the rotation) and the launch
        // data (it has nothing else to render from).
        if (!isChangingConfigurations) {
            launchKey?.let { retireLaunch(it) }
        }
        super.onDestroy()
    }
}
