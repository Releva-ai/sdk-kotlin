package ai.releva.sdk.ui.banner

import ai.releva.sdk.types.response.BannerResponse
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View

/**
 * The nine author-controlled `cssStyles` keys that describe a banner's *chrome* — the card around
 * the rendered design — and where that card sits. The keys, their vocabularies and their defaults
 * are the same contract all four mobile SDKs and the web SDK implement.
 *
 * The whole design is the compatibility rule: **a value equal to its documented default must
 * change nothing on this platform.** So every accessor here answers null — or the caller's own
 * fallback — for a key the author left alone, and each call site reads that as "run exactly the
 * code that ran before these keys existed". Only a value that differs is author intent. A value
 * that fails to parse is treated as absent for the same reason: an unparseable string must fall
 * back to the default rather than reach a layout call.
 *
 * `auto` and the other defaults therefore mean "whatever this SDK does today", not "what the web
 * does"; this is an adoption of the contract, not a port of the web SDK's layout.
 */
internal class BannerChrome(
    private val cssStyles: Map<String, Any?>,
    private val displayPosition: String?,
    private val density: Float
) {

    /** The card's colour, or null to leave the colour the call site already uses in place. */
    val backgroundColor: Int?
        get() = DesignRenderer.parseColor(authored(CARD_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR))

    /**
     * The corner radius in device pixels, or null to leave today's corner treatment alone. The key
     * is a unitless number — the web SDK appends `px` — so it is a CSS pixel and scales with the
     * display density like every other value in a design.
     */
    val borderRadiusPx: Float?
        get() = authored(CARD_BORDER_RADIUS, DEFAULT_BORDER_RADIUS)
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?.times(density)

    /** Where the content sits inside the card, or null at the default. */
    val contentGravity: Int?
        get() = authored(CONTENT_VERTICAL_ALIGN, DEFAULT_CONTENT_VERTICAL_ALIGN)
            ?.let { VERTICAL_ALIGNMENTS[it.lowercase()] }

    fun widthPx(availableWidth: Int): Int? = size(CARD_WIDTH, availableWidth)

    fun heightPx(availableHeight: Int): Int? = size(CARD_HEIGHT, availableHeight)

    fun offsetVerticalPx(availableHeight: Int): Int? = offset(CARD_OFFSET_VERTICAL, availableHeight)

    fun offsetHorizontalPx(availableWidth: Int): Int? = offset(CARD_OFFSET_HORIZONTAL, availableWidth)

    /**
     * Vertical placement of the card: the authored key, else [displayPosition] as this SDK reads it
     * today, else [fallback] — the gravity the calling banner type lays out with now.
     *
     * `displayPosition` goes through a lookup of its four frozen values rather than an inline
     * comparison, so a value from the other axis, an unexpected one or null misses the table and
     * lands on [fallback] instead of falling through an implicit else.
     */
    fun verticalGravity(fallback: Int): Int =
        authored(CARD_POSITION_VERTICAL, AUTO)?.let { VERTICAL_ALIGNMENTS[it.lowercase()] }
            ?: displayPosition?.let { VERTICAL_FROM_DISPLAY_POSITION[it] }
            ?: fallback

    /** The horizontal counterpart of [verticalGravity]. */
    fun horizontalGravity(fallback: Int): Int =
        authored(CARD_POSITION_HORIZONTAL, AUTO)?.let { HORIZONTAL_ALIGNMENTS[it.lowercase()] }
            ?: displayPosition?.let { HORIZONTAL_FROM_DISPLAY_POSITION[it] }
            ?: fallback

    /**
     * Paints the card [color], which stays a flat background — exactly what the call sites set
     * today — until a radius is authored and the background has to become a drawable to carry it.
     * A null [color] with no radius leaves the view's background untouched, which is what a bar's
     * card has today.
     */
    fun applyCardBackground(view: View, color: Int?) {
        val radius = borderRadiusPx
        when {
            radius != null -> view.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(color ?: Color.TRANSPARENT)
            }
            color != null -> view.setBackgroundColor(color)
        }
    }

    /**
     * The author's value for [key], or null when it is absent or equal to [default]. The
     * comparison is trimmed and lower-cased because the defaults are literals produced in another
     * repo: a case near-miss must not read as intent.
     */
    private fun authored(key: String, default: String): String? {
        val resolved = (cssStyles[key]?.toString() ?: default).trim()
        return if (resolved.equals(default, ignoreCase = true)) null else resolved
    }

    private fun size(key: String, availablePx: Int): Int? =
        authored(key, AUTO)?.let { lengthPx(it, availablePx, allowNegative = false) }

    private fun offset(key: String, availablePx: Int): Int? =
        authored(key, AUTO)?.let { lengthPx(it, availablePx, allowNegative = true) }

    /**
     * A length in the closed vocabulary the SDKs agree on: a number followed by `px` — a CSS pixel,
     * scaled to this display's density — or by `%` of [availablePx], the axis the value applies to.
     * Nothing else is accepted server-side, so no `vw`, `rem` or `calc()` has to parse here; they
     * return null and the caller keeps its default. Offsets may also be a bare `0` and may be
     * negative; sizes may be neither.
     */
    private fun lengthPx(value: String, availablePx: Int, allowNegative: Boolean): Int? {
        val text = value.lowercase()
        if (allowNegative && text == "0") return 0
        val px = when {
            text.endsWith("px") -> text.dropLast(2).toFloatOrNull()?.times(density)
            text.endsWith("%") -> text.dropLast(1).toFloatOrNull()?.div(100f)?.times(availablePx)
            else -> null
        } ?: return null
        return if (!allowNegative && px < 0f) null else px.toInt()
    }

    companion object {
        fun of(banner: BannerResponse, context: Context): BannerChrome = BannerChrome(
            banner.cssStyles,
            banner.displayPosition,
            context.resources.displayMetrics.density
        )

        private const val AUTO = "auto"
        private const val CARD_BACKGROUND_COLOR = "cardBackgroundColor"
        private const val CARD_WIDTH = "cardWidth"
        private const val CARD_HEIGHT = "cardHeight"
        private const val CARD_BORDER_RADIUS = "cardBorderRadius"
        private const val CONTENT_VERTICAL_ALIGN = "contentVerticalAlign"
        private const val CARD_POSITION_VERTICAL = "cardPositionVertical"
        private const val CARD_POSITION_HORIZONTAL = "cardPositionHorizontal"
        private const val CARD_OFFSET_VERTICAL = "cardOffsetVertical"
        private const val CARD_OFFSET_HORIZONTAL = "cardOffsetHorizontal"

        private const val DEFAULT_BACKGROUND_COLOR = "#fefefe"
        private const val DEFAULT_BORDER_RADIUS = "0"
        private const val DEFAULT_CONTENT_VERTICAL_ALIGN = "top"

        private val VERTICAL_ALIGNMENTS = mapOf(
            "top" to Gravity.TOP,
            "center" to Gravity.CENTER_VERTICAL,
            "bottom" to Gravity.BOTTOM
        )
        private val HORIZONTAL_ALIGNMENTS = mapOf(
            "left" to Gravity.START,
            "center" to Gravity.CENTER_HORIZONTAL,
            "right" to Gravity.END
        )

        // `displayPosition`'s vocabulary, kept exactly as wide as it is today — the four values are
        // validated server-side and no fifth will be added, and these tables are deliberately not
        // the ones above: the card keys understand `center`, `displayPosition` never will.
        private val VERTICAL_FROM_DISPLAY_POSITION = mapOf(
            "top" to Gravity.TOP,
            "bottom" to Gravity.BOTTOM
        )
        private val HORIZONTAL_FROM_DISPLAY_POSITION = mapOf(
            "left" to Gravity.START,
            "right" to Gravity.END
        )
    }
}
