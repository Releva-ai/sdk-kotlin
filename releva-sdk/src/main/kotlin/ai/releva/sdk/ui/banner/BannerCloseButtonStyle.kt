package ai.releva.sdk.ui.banner

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * The five author-controlled `cssStyles` keys that describe a banner's *close control*, resolved
 * into the handful of numbers the three display types that draw one need. The keys, their
 * vocabularies and their defaults are the contract all four mobile SDKs implement against the web
 * SDK's `.closeBtnHolder`, which interpolates them as raw CSS.
 *
 * The other six close-button keys the API serves — `closeButtonSymbol`, `closeButtonPadding`,
 * `closeButtonFontWeight`, `closeButtonLineHeight`, `closeButtonTopPosition` and
 * `closeButtonRightPosition` — are web-only and are deliberately not read: the control keeps this
 * SDK's own glyph and the placement its call site already gives it.
 *
 * Every value is read from `cssStyles` alone. The Unlayer design's `popupCloseButton_iconColor`
 * and `popupCloseButton_backgroundColor` used to win over these keys here; the web SDK never read
 * them and that override is retired.
 *
 * A key that is absent, blank or unparseable falls back to its default, which is the CSS the API
 * fills a missing key with — so a banner whose author never opened these controls resolves to a
 * black ✕ on a white circle with no border, exactly what the web renders for it.
 */
internal data class BannerCloseButtonStyle(
    /** The ✕ glyph's colour. */
    val iconColor: Int,

    /** The button's fill. */
    val backgroundColor: Int,

    /**
     * The border's width in dp and its colour, from the CSS shorthand (`1px solid #fff`). "No
     * border" — the default, and what every banner in production serves — is a zero-width
     * transparent stroke rather than a null, so the call site hands both straight to
     * `GradientDrawable.setStroke` with no branch of its own.
     */
    val borderWidthDp: Float,
    val borderColor: Int,

    /** Corner radius in dp, already capped at half of [sideDp] so it can only round, never clip. */
    val cornerRadiusDp: Float,

    /**
     * The side of the square that is actually painted: `max(32, closeFontSize + 18)`, so the
     * default `closeFontSize` of 14 keeps the 32dp button this SDK has always drawn.
     */
    val sideDp: Int,

    /**
     * The side of the view, which is what receives a touch: Android's 48dp minimum, and never
     * less than [sideDp]. The difference is a transparent ring around the painted square, so a
     * call site places the control by [sideDp]'s corner — where it has always sat — and pulls the
     * view itself back by half of it.
     */
    val tapTargetDp: Int,

    /**
     * The box the ✕ drawable is centred in. `closeFontSize` is a font size on the web and this
     * SDK draws a drawable, which carries its own transparent margin: the extra 6 is what makes
     * the default of 14 reproduce the 20dp box inside the 32dp button.
     */
    val glyphSizeDp: Int
) {
    companion object {
        fun of(cssStyles: Map<String, Any?>): BannerCloseButtonStyle {
            val fontSize = (number(cssStyles, CLOSE_FONT_SIZE) ?: 14f).roundToInt().coerceIn(8, 26)
            val side = maxOf(32, fontSize + 18)
            val (borderWidth, borderColor) = border(cssStyles[CLOSE_BUTTON_BORDER])
            return BannerCloseButtonStyle(
                iconColor = DesignRenderer.parseColor(cssStyles[CLOSE_BUTTON_COLOR]) ?: Color.BLACK,
                backgroundColor = DesignRenderer.parseColor(cssStyles[CLOSE_BUTTON_BACKGROUND_COLOR])
                    ?: Color.WHITE,
                borderWidthDp = borderWidth,
                borderColor = borderColor,
                cornerRadiusDp = (number(cssStyles, CLOSE_BUTTON_BORDER_RADIUS) ?: 20f)
                    .coerceIn(0f, side / 2f),
                sideDp = side,
                tapTargetDp = maxOf(48, side),
                glyphSizeDp = fontSize + 6
            )
        }

        /**
         * The CSS border shorthand, reduced to the two things a stroke needs. The colour is any
         * token [DesignRenderer.parseColor] accepts and the width is the `Npx` (or bare number)
         * token beside it, defaulting to 1 where only a colour is given. `none`, `0`, a blank and
         * anything carrying no parseable colour all mean no border, which is what the web draws
         * for an unauthored banner.
         */
        private fun border(value: Any?): Pair<Float, Int> {
            val text = value?.toString()?.trim().orEmpty()
            if (NO_STYLE.containsMatchIn(text)) return NO_BORDER
            val token = COLOR_TOKEN.find(text) ?: return NO_BORDER
            val color = DesignRenderer.parseColor(token.value) ?: return NO_BORDER
            val width = text.removeRange(token.range).trim().split(WHITESPACE)
                .firstNotNullOfOrNull { WIDTH_TOKEN.matchEntire(it)?.groupValues?.get(1)?.toFloatOrNull() }
            return (width ?: 1f) to color
        }

        /** A unitless or `px` length; blank and unparseable both answer null, as absent does. */
        private fun number(cssStyles: Map<String, Any?>, key: String): Float? =
            cssStyles[key]?.toString()?.trim()?.lowercase()?.removeSuffix("px")?.toFloatOrNull()

        private const val CLOSE_BUTTON_COLOR = "closeButtonColor"
        private const val CLOSE_BUTTON_BACKGROUND_COLOR = "closeButtonBackgroundColor"
        private const val CLOSE_BUTTON_BORDER = "closeButtonBorder"
        private const val CLOSE_FONT_SIZE = "closeFontSize"
        private const val CLOSE_BUTTON_BORDER_RADIUS = "closeButtonBorderRadius"

        private val NO_BORDER = 0f to Color.TRANSPARENT
        private val WHITESPACE = Regex("""\s+""")
        private val COLOR_TOKEN =
            Regex("""rgba?\([^)]*\)|#[0-9a-f]{3,8}(?![0-9a-z])|transparent""", RegexOption.IGNORE_CASE)
        private val NO_STYLE = Regex("""(?:^|\s)(?:none|hidden)(?:\s|$)""", RegexOption.IGNORE_CASE)
        private val WIDTH_TOKEN = Regex("""(\d+(?:\.\d+)?)(?:px)?""")
    }
}
