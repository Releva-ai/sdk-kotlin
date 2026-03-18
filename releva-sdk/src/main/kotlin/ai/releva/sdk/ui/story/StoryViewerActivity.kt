package ai.releva.sdk.ui.story

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.types.response.StoryResponse
import kotlinx.coroutines.cancel
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
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var contentContainer: FrameLayout
    private lateinit var progressContainer: LinearLayout
    private val progressBars = mutableListOf<View>()
    private val progressFills = mutableListOf<View>()

    companion object {
        private const val TAG = "StoryViewer"
        /** Tag value set on views that need touch forwarding (e.g. carousel). */
        const val INTERACTIVE_VIEW_TAG = "releva_interactive"
        private const val EXTRA_LAUNCH_KEY = "releva_story_key"

        private val pendingLaunches = java.util.concurrent.ConcurrentHashMap<String, PendingLaunchData>()

        private data class PendingLaunchData(
            val story: StoryResponse,
            val client: RelevaClient,
            val onLinkTap: ((String) -> Unit)?,
            val onClose: (() -> Unit)?
        )

        fun launch(
            context: Context,
            story: StoryResponse,
            client: RelevaClient,
            onLinkTap: ((String) -> Unit)? = null,
            onClose: (() -> Unit)? = null
        ) {
            val key = java.util.UUID.randomUUID().toString()
            pendingLaunches[key] = PendingLaunchData(story, client, onLinkTap, onClose)
            val intent = Intent(context, StoryViewerActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_KEY, key)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var story: StoryResponse
    private var client: RelevaClient? = null
    private var onLinkTap: ((String) -> Unit)? = null
    private var onCloseCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(EXTRA_LAUNCH_KEY) ?: run { finish(); return }
        val data = pendingLaunches.remove(key) ?: run { finish(); return }

        story = data.story
        client = data.client
        onLinkTap = data.onLinkTap
        onCloseCallback = data.onClose

        if (story.slides.isEmpty()) {
            finish()
            return
        }

        setupUI()
        trackEvent("storyImpression")
        trackSlideView()
        startSlideTimer()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun setupUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(getSlideBackgroundColor(story.slides[0]))
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Content area below progress bars
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
        renderSlideContent(story.slides[0])

        setContentView(root)
    }

    private fun startSlideTimer() {
        progressAnimator?.cancel()

        val slide = story.slides[currentSlideIndex]
        val duration = slide.durationSeconds * 1000L

        // Update progress fills for completed/current slides
        updateProgressBars()

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
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    goToNextSlide()
                }
            })
            start()
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
        progressAnimator?.removeAllListeners()
        progressAnimator?.cancel()

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
        progressAnimator?.removeAllListeners()
        progressAnimator?.cancel()

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
            contentContainer.addView(actionBtn)
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

    override fun onDestroy() {
        progressAnimator?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
