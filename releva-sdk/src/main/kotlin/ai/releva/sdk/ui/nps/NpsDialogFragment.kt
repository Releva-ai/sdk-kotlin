package ai.releva.sdk.ui.nps

import ai.releva.sdk.types.response.NpsConfig
import ai.releva.sdk.types.response.RelevaResponse
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * NPS survey dialog that supports both bottom sheet and centered modal positions.
 * Three screens: score selection -> optional follow-up -> thank you.
 */
class NpsDialogFragment : BottomSheetDialogFragment() {

    private var selectedScore: Int? = null
    private var submitted = false
    private var submitting = false
    private var followUpInput: EditText? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Set only by the deprecated three-argument newInstance, and only on the instance it
    // returns — an instance field, same as this callback travelled on master, so it cannot
    // leak into a survey NpsDisplayManager.attach() shows separately. It also cannot survive
    // a configuration change: a field set here is gone once the FragmentManager recreates
    // this fragment through the no-arg constructor, same as config was before this fix. A
    // dialog recreated that way falls back to NpsDisplayManager's callbacks below, which is
    // where the deprecation is steering every caller anyway.
    private var directOnSubmit: (suspend (String, Int, String?) -> Unit)? = null
    private var directOnSkip: (() -> Unit)? = null

    private lateinit var contentContainer: FrameLayout

    companion object {
        private const val TAG = "NpsDialogFragment"
        private const val ARG_CONFIG = "releva_nps_config"
        private const val STATE_SCORE = "releva_nps_score"
        private const val STATE_SUBMITTED = "releva_nps_submitted"
        private const val STATE_COMMENT = "releva_nps_comment"

        /**
         * The survey travels in [arguments] rather than in a field: a configuration change
         * destroys the fragment and the FragmentManager recreates it through the no-arg
         * constructor, so a field set here is gone by the time the successor's
         * `onCreateDialog` runs — and it dismissed itself. [arguments] is restored for the
         * successor. [NpsConfig] is neither Parcelable nor Serializable, so it goes in as
         * the JSON its own `toMap`/`fromMap` already round-trip through.
         *
         * The submit and skip callbacks are not passed at all; they cannot go in a Bundle,
         * so the fragment reads them from [NpsDisplayManager] when it needs them.
         */
        fun newInstance(config: NpsConfig): NpsDialogFragment {
            return NpsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG, JSONObject(config.toMap()).toString())
                }
            }
        }

        /**
         * Retained so a caller who built the dialog directly — rather than through
         * [NpsDisplayManager.attach] — still compiles; [config] and its callbacks were the
         * whole signature before this fix.
         *
         * [onSubmit] and [onSkip] are kept on the returned instance only — [directOnSubmit] /
         * [directOnSkip] — exactly as they travelled on master, so calling this cannot change
         * what a survey shown separately through [NpsDisplayManager.attach] submits to. The
         * trade is the one the deprecation message names: an instance field cannot survive a
         * configuration change, so a dialog built this way and then recreated falls back to
         * [NpsDisplayManager]'s callbacks — null for a caller who never calls
         * [NpsDisplayManager.setOnSubmit] — and logs the warning below instead of submitting.
         * That is worse than this call surviving intact, but it is confined to the path this
         * deprecation already tells callers to move off of, and it is not worse than master,
         * where the whole dialog was dismissed outright on recreation.
         */
        @Deprecated(
            "Register callbacks on NpsDisplayManager; they cannot survive recreation on the fragment."
        )
        fun newInstance(
            config: NpsConfig,
            onSubmit: suspend (String, Int, String?) -> Unit,
            onSkip: (() -> Unit)? = null
        ): NpsDialogFragment {
            return newInstance(config).apply {
                directOnSubmit = onSubmit
                directOnSkip = onSkip
            }
        }
    }

    override fun onDestroyView() {
        scope.cancel()
        followUpInput = null
        super.onDestroyView()
    }

    /**
     * Saves what the arguments cannot carry: how far through the survey the user is. The
     * step's views are built in code without ids, so the platform's own view-state saving
     * does not reach the typed comment either.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedScore?.let { outState.putInt(STATE_SCORE, it) }
        outState.putBoolean(STATE_SUBMITTED, submitted)
        followUpInput?.let { outState.putString(STATE_COMMENT, it.text.toString()) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val cfg = configFromArguments() ?: run {
            dismissAllowingStateLoss()
            return super.onCreateDialog(savedInstanceState)
        }

        savedInstanceState?.let {
            if (it.containsKey(STATE_SCORE)) selectedScore = it.getInt(STATE_SCORE)
            submitted = it.getBoolean(STATE_SUBMITTED)
        }

        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        contentContainer = FrameLayout(requireContext())
        val score = selectedScore
        val followUp = score?.let { cfg.followUp?.forScore(it) }
        when {
            score != null && submitted -> showThankYouStep(cfg, score)
            score != null && followUp != null ->
                showFollowUpStep(cfg, followUp, savedInstanceState?.getString(STATE_COMMENT))
            // A score with no follow-up question submits immediately, so a restored score
            // that reaches here is one whose submission the recreation interrupted. The
            // score step lets the user answer again rather than stranding them.
            else -> showScoreStep(cfg)
        }

        dialog.setContentView(contentContainer)
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        return dialog
    }

    /**
     * `internal` so a test can assert on the [NpsConfig] that comes back through the real
     * `org.json` hop, including fields this fragment never renders.
     */
    @VisibleForTesting
    internal fun configFromArguments(): NpsConfig? {
        val json = arguments?.getString(ARG_CONFIG) ?: return null
        return NpsConfig.fromMap(RelevaResponse.jsonObjectToMap(JSONObject(json)))
    }

    private fun resolveColors(config: NpsConfig): Triple<Int, Int, Int> {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val appearance = config.appearance

        val primary = if (isDark && appearance.dark?.primaryColor != null)
            parseHexColor(appearance.dark.primaryColor) ?: parseHexColor(appearance.primaryColor) ?: Color.parseColor("#6C3FC4")
        else parseHexColor(appearance.primaryColor) ?: Color.parseColor("#6C3FC4")

        val bg = if (isDark && appearance.dark?.backgroundColor != null)
            parseHexColor(appearance.dark.backgroundColor) ?: parseHexColor(appearance.backgroundColor) ?: Color.WHITE
        else parseHexColor(appearance.backgroundColor) ?: Color.WHITE

        val text = if (isDark && appearance.dark?.textColor != null)
            parseHexColor(appearance.dark.textColor) ?: parseHexColor(appearance.textColor) ?: Color.parseColor("#1A1A1A")
        else parseHexColor(appearance.textColor) ?: Color.parseColor("#1A1A1A")

        return Triple(primary, bg, text)
    }

    private fun showScoreStep(config: NpsConfig) {
        contentContainer.removeAllViews()
        val (primary, bg, textCol) = resolveColors(config)
        val ctx = requireContext()
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Question
        root.addView(TextView(ctx).apply {
            text = config.question
            setTextColor(textCol)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
        })

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        })

        // Score buttons 0-10
        val scoreRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        for (score in 0..10) {
            if (score > 0) {
                scoreRow.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(3), 0)
                })
            }

            val btnBg = GradientDrawable().apply {
                setColor(adjustAlpha(primary, 0.08f))
                setStroke(dp(1), adjustAlpha(primary, 0.3f))
                cornerRadius = when (config.appearance.buttonStyle) {
                    "pill" -> dp(18).toFloat()
                    "rounded" -> dp(8).toFloat()
                    else -> 0f
                }
            }

            val btn = TextView(ctx).apply {
                this.text = "$score"
                setTextColor(primary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = btnBg
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                setOnClickListener { onScoreSelected(config, score) }
            }
            scoreRow.addView(btn)
        }
        root.addView(scoreRow)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        })

        // Scale labels
        val labelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        config.scaleLowLabel?.let {
            labelRow.addView(TextView(ctx).apply {
                this.text = it
                setTextColor(adjustAlpha(textCol, 0.6f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        config.scaleHighLabel?.let {
            labelRow.addView(TextView(ctx).apply {
                this.text = it
                setTextColor(adjustAlpha(textCol, 0.6f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        root.addView(labelRow)

        // Skip button
        config.skipLabel?.let { skipText ->
            root.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
            })

            val skipBtn = TextView(ctx).apply {
                text = skipText
                setTextColor(adjustAlpha(textCol, 0.5f))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    (directOnSkip ?: NpsDisplayManager.skipCallback())?.invoke()
                    dismissAllowingStateLoss()
                }
            }
            root.addView(skipBtn)
        }

        contentContainer.addView(root)
    }

    private fun onScoreSelected(config: NpsConfig, score: Int) {
        selectedScore = score
        val followUp = config.followUp?.forScore(score)
        if (followUp != null) {
            showFollowUpStep(config, followUp, null)
        } else {
            submitScore(config, score, null)
        }
    }

    private fun showFollowUpStep(config: NpsConfig, followUpQuestion: String, initialComment: String?) {
        contentContainer.removeAllViews()
        val (primary, bg, textCol) = resolveColors(config)
        val ctx = requireContext()
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(24))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Follow-up question
        root.addView(TextView(ctx).apply {
            text = followUpQuestion
            setTextColor(textCol)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            paint.isFakeBoldText = true
        })

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        })

        // Comment input
        val editText = EditText(ctx).apply {
            hint = "Your feedback..."
            setHintTextColor(adjustAlpha(textCol, 0.4f))
            setTextColor(textCol)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minLines = 3
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(adjustAlpha(textCol, 0.05f))
                setStroke(dp(1), adjustAlpha(textCol, 0.2f))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(editText)
        followUpInput = editText

        root.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val focused = root.findFocus()
                if (focused is EditText) {
                    val outRect = android.graphics.Rect()
                    focused.getGlobalVisibleRect(outRect)
                    if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        focused.clearFocus()
                        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(focused.windowToken, 0)
                    }
                }
            }
            false
        }

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        })

        // Submit button
        val submitBtnBg = GradientDrawable().apply {
            setColor(primary)
            cornerRadius = when (config.appearance.buttonStyle) {
                "pill" -> dp(24).toFloat()
                "rounded" -> dp(8).toFloat()
                else -> 0f
            }
        }

        val submitBtn = TextView(ctx).apply {
            this.text = config.submitLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = submitBtnBg
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isEnabled = !config.followUpRequired
            setOnClickListener {
                if (!submitting) {
                    editText.clearFocus()
                    val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(editText.windowToken, 0)
                    val comment = editText.text.toString().trim()
                    submitScore(config, selectedScore!!, comment.ifEmpty { null })
                }
            }
        }

        if (config.followUpRequired) {
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    submitBtn.isEnabled = !s.isNullOrBlank()
                    submitBtn.alpha = if (submitBtn.isEnabled) 1f else 0.4f
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            submitBtn.alpha = 0.4f
        }

        // After the watcher is wired, so a comment restored into a required follow-up
        // re-enables the submit button the same way typing it would have.
        initialComment?.let { editText.setText(it) }

        root.addView(submitBtn)

        val scrollView = android.widget.ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = true
        }
        scrollView.addView(root)
        contentContainer.addView(scrollView)

        // When the EditText gains focus (keyboard opens), scroll so the
        // submit button stays visible above the keyboard.
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollView.post {
                    scrollView.smoothScrollTo(0, submitBtn.bottom)
                }
            }
        }
    }

    private fun submitScore(config: NpsConfig, score: Int, comment: String?) {
        submitting = true
        scope.launch {
            try {
                val onSubmit = directOnSubmit ?: NpsDisplayManager.submitCallback()
                if (onSubmit != null) {
                    onSubmit(config.token, score, comment)
                } else {
                    // An integration mistake rather than a runtime one: the thank-you step
                    // below still shows, so log it rather than letting it pass in silence.
                    Log.w(TAG, "NPS submit reached with no onSubmit callback registered; feedback was not sent")
                }
            } catch (e: CancellationException) {
                // onDestroyView's scope.cancel() resumes a suspended onSubmit with this. Let
                // it propagate: the coroutine machinery treats a CancellationException on an
                // already-cancelled job as normal shutdown, not a crash, but only if it is
                // rethrown rather than swallowed here. Falling through to showThankYouStep
                // below on a fragment mid-detach would call requireContext() on one with no
                // host and crash instead.
                throw e
            } catch (_: Exception) {
                // Submission failures are silent per spec
            }
            submitting = false
            submitted = true
            showThankYouStep(config, score)
        }
    }

    private fun showThankYouStep(config: NpsConfig, score: Int) {
        followUpInput = null
        contentContainer.removeAllViews()
        val (primary, bg, textCol) = resolveColors(config)
        val ctx = requireContext()
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(24), dp(20), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Checkmark icon (using Unicode)
        root.addView(TextView(ctx).apply {
            this.text = "\u2713"
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
        })

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        })

        // Thank you message
        val thankYouText = config.thankYou?.forScore(score) ?: "Thank you!"
        root.addView(TextView(ctx).apply {
            this.text = thankYouText
            setTextColor(textCol)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        })

        contentContainer.addView(root)

        // Auto-dismiss after 2 seconds
        handler.postDelayed({
            if (isAdded) dismissAllowingStateLoss()
        }, 2000)
    }

    private fun parseHexColor(hex: String?): Int? {
        if (hex.isNullOrEmpty()) return null
        return try {
            val clean = hex.removePrefix("#")
            val fullHex = if (clean.length == 6) "FF$clean" else clean
            Color.parseColor("#$fullHex")
        } catch (e: Exception) {
            null
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
