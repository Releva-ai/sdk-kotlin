package ai.releva.sdk.ui.nps

import ai.releva.sdk.types.response.NpsConfig
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NPS survey dialog that supports both bottom sheet and centered modal positions.
 * Three screens: score selection -> optional follow-up -> thank you.
 */
class NpsDialogFragment : BottomSheetDialogFragment() {

    private var config: NpsConfig? = null
    private var onSubmit: (suspend (String, Int, String?) -> Unit)? = null
    private var onSkip: (() -> Unit)? = null
    private var selectedScore: Int? = null
    private var submitting = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var contentContainer: FrameLayout

    companion object {
        fun newInstance(
            config: NpsConfig,
            onSubmit: suspend (String, Int, String?) -> Unit,
            onSkip: (() -> Unit)? = null
        ): NpsDialogFragment {
            return NpsDialogFragment().apply {
                this.config = config
                this.onSubmit = onSubmit
                this.onSkip = onSkip
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val cfg = config ?: run {
            dismissAllowingStateLoss()
            return super.onCreateDialog(savedInstanceState)
        }

        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        contentContainer = FrameLayout(requireContext())
        showScoreStep(cfg)

        dialog.setContentView(contentContainer)
        return dialog
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
                    onSkip?.invoke()
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
            showFollowUpStep(config, followUp)
        } else {
            submitScore(config, score, null)
        }
    }

    private fun showFollowUpStep(config: NpsConfig, followUpQuestion: String) {
        contentContainer.removeAllViews()
        val (primary, bg, textCol) = resolveColors(config)
        val ctx = requireContext()
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(24))
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(adjustAlpha(textCol, 0.05f))
                setStroke(dp(1), adjustAlpha(textCol, 0.2f))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(editText)

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

        root.addView(submitBtn)
        contentContainer.addView(root)
    }

    private fun submitScore(config: NpsConfig, score: Int, comment: String?) {
        submitting = true
        scope.launch {
            try {
                onSubmit?.invoke(config.token, score, comment)
            } catch (_: Exception) {
                // Submission failures are silent per spec
            }
            submitting = false
            showThankYouStep(config, score)
        }
    }

    private fun showThankYouStep(config: NpsConfig, score: Int) {
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
            java.lang.Long.parseLong(fullHex, 16).toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
