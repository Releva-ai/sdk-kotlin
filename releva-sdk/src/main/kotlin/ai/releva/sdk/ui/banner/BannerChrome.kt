package ai.releva.sdk.ui.banner

import ai.releva.sdk.types.response.BannerResponse
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

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

    /**
     * The card's colour, or null to leave the colour the call site already uses in place. That
     * "in place" fallback, not this property's own default, is what an author sees at
     * `#fefefe` — the documented default — since [authored] treats the two as equal and answers
     * null for either. An author who explicitly sets `#fefefe` on a bar (whose fallback is
     * transparent, not near-white) therefore gets a transparent card, the same as one who never
     * touched the key at all. Deliberate: painting `#fefefe` on every bar by default would be a
     * worse regression than one author-typed value being read as "unchanged".
     */
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

    /**
     * The popup card's width before [PopupCardLayout] caps it to the box it is placed in: the
     * authored `cardWidth`, else the design's own `popupWidth` (a CSS pixel, as every dimension in
     * a design is), else 600 — sdk-swift's order and default (PR #23), which sdk-react-native
     * adopted on 2026-10-05. 600px is what every production banner that carries the key says.
     */
    fun popupWidthPx(availableWidth: Int, designPopupWidth: Any?): Int =
        widthPx(availableWidth)
            ?: DesignRenderer.parseDimensionRaw(designPopupWidth)
                ?.takeIf { it > 0f }?.times(density)?.roundToInt()?.takeIf { it > 0 }
            ?: (DEFAULT_POPUP_WIDTH * density).roundToInt()

    /**
     * The popup card's corner radius in px: the authored `cardBorderRadius`, else the design's own
     * `borderRadius`, else 10 — again sdk-swift's order and default. Unlike [borderRadiusPx] this
     * is never null: a popup is always a rounded card now, where a bar and a flyout keep today's
     * square corners until a radius is authored.
     */
    fun popupCornerRadiusPx(designBorderRadius: Any?): Float =
        borderRadiusPx
            ?: DesignRenderer.parseDimensionRaw(designBorderRadius)?.takeIf { it >= 0f }?.times(density)
            ?: DEFAULT_POPUP_RADIUS * density

    fun heightPx(availableHeight: Int): Int? = size(CARD_HEIGHT, availableHeight)

    /**
     * Displaces [view] by the authored offsets, from wherever its gravity and layout params have
     * already put it — a translation, not a margin and not a padding.
     *
     * That is the whole of what this method decides, and it is the one displacement that means the
     * same thing in all three of the states an axis can be in here: the card spans it, the card is
     * pinned to one of its edges, or the card is centred on it. A margin does not. `FrameLayout`
     * centres a child's *margin box*, so its `childTop = … + topMargin - bottomMargin` cancels an
     * equal pair exactly: an author who centres a card and asks for `cardOffsetVertical: '40px'`
     * gets nothing at all. Nor does a padding, which eats the card's own content area instead of
     * moving the card, leaving the design — already rendered to the card's width — no longer
     * fitting it. The other three mobile SDKs translate for the same reason, and hit the same wall
     * first: SwiftUI's `.offset(x:y:)`, React Native's `transform: translate`,
     * Flutter's `Transform.translate`.
     *
     * Sign, matching them: a positive offset moves the card *away from the edge it is anchored
     * to*, so it is negated on a `bottom` or `end` anchor and applied as-is everywhere else — on a
     * `top` or `start` anchor, and on a centred axis, where it moves the card down and right.
     *
     * [availableWidth] and [availableHeight] are what a `%` offset resolves against, as for a size.
     */
    /**
     * The authored `cardOffset*` in px on one axis, or null at `auto` — the amount
     * [applyOffsets] will translate the card by, before its sign is resolved against the anchor.
     *
     * Exposed so a caller can ask whether a card it has just placed will STAY where it placed it.
     * A translation happens after both the margin and the content padding are decided, so a card
     * that fits inside the visible box can still be moved back onto a system bar by one.
     */
    fun verticalOffsetPx(availableHeight: Int): Int? = offset(CARD_OFFSET_VERTICAL, availableHeight)

    /** The horizontal twin of [verticalOffsetPx]. */
    fun horizontalOffsetPx(availableWidth: Int): Int? = offset(CARD_OFFSET_HORIZONTAL, availableWidth)

    fun applyOffsets(
        view: View,
        verticalGravity: Int,
        horizontalGravity: Int,
        availableWidth: Int,
        availableHeight: Int
    ) {
        offset(CARD_OFFSET_VERTICAL, availableHeight)?.let {
            val awayIsUp = (verticalGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
            view.translationY = (if (awayIsUp) -it else it).toFloat()
        }
        offset(CARD_OFFSET_HORIZONTAL, availableWidth)?.let {
            // `Gravity.START`/`END` carry a relative-direction bit above the absolute ones, so the
            // mask has to be the pair itself; these two tables never emit `LEFT`/`RIGHT`.
            val awayIsLeft = (horizontalGravity and (Gravity.START or Gravity.END)) == Gravity.END
            view.translationX = (if (awayIsLeft) -it else it).toFloat()
        }
    }

    /**
     * Vertical placement of the card: the authored key, else [fallback] — whatever the calling
     * banner type places the card with on this axis today.
     */
    fun verticalGravity(fallback: Int): Int =
        authored(CARD_POSITION_VERTICAL, AUTO)?.let { VERTICAL_ALIGNMENTS[it.lowercase()] }
            ?: fallback

    /** The horizontal counterpart of [verticalGravity]. */
    fun horizontalGravity(fallback: Int): Int =
        authored(CARD_POSITION_HORIZONTAL, AUTO)?.let { HORIZONTAL_ALIGNMENTS[it.lowercase()] }
            ?: fallback

    /**
     * The vertical placement [displayPosition] asks for, else [fallback] — the *bar's* fallback to
     * [verticalGravity], and nobody else's. This is the `displayPosition == "bottom"` the bar
     * branches on today, and the bar's vertical axis is the only vertical axis in this SDK that
     * has ever read the column: the popup and the flyout pass a constant here, because `auto`
     * means "what this SDK does today" and for them that is not `displayPosition`.
     *
     * The lookup replaces the inline comparison so that a value from the other axis, an unexpected
     * one, or null misses the table and lands on [fallback] instead of falling through an implicit
     * else. The vocabulary stays exactly as wide as it is today.
     */
    fun verticalFromDisplayPosition(fallback: Int): Int =
        VERTICAL_FROM_DISPLAY_POSITION[displayPosition] ?: fallback

    /**
     * The horizontal counterpart of [verticalFromDisplayPosition], read by the *flyout* alone: it
     * is the `displayPosition == "left"` that type branches on today. The popup and the bar pass a
     * constant on this axis, as they do now.
     */
    fun horizontalFromDisplayPosition(fallback: Int): Int =
        HORIZONTAL_FROM_DISPLAY_POSITION[displayPosition] ?: fallback

    /**
     * Paints the card [color], which stays a flat background — exactly what the call sites set
     * today — until a radius is authored and the background has to become a drawable to carry it.
     * `color` is non-null: every call site resolves its own fallback before calling this, so there
     * is never a "nothing to paint" case for the type to leave room for.
     *
     * A rounded [GradientDrawable] only rounds its own painting; it does not clip the view's
     * children, so a design with a body or row background colour would still square off the
     * corners it just rounded. [View.setClipToOutline] closes that: the platform derives the
     * outline from this same background (a [GradientDrawable] with a non-zero corner radius emits
     * a rounded-rect outline), so descendants are clipped to the same rounded rect that is drawn.
     * Inert whenever no radius is authored, since `clipToOutline` is only set `true` in that branch.
     *
     * [fallbackRadiusPx] is the radius to draw when none is authored — the popup's own default
     * card corner ([popupCornerRadiusPx]). Null, as the bar and the flyout pass it, keeps the flat
     * background they have always had.
     */
    fun applyCardBackground(view: View, color: Int, fallbackRadiusPx: Float? = null) {
        val radius = borderRadiusPx ?: fallbackRadiusPx
        if (radius != null) {
            view.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(color)
            }
            view.clipToOutline = true
        } else {
            view.setBackgroundColor(color)
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
     * return null and the caller keeps its default.
     *
     * Offsets may be negative and sizes may not, which is what [allowNegative] gates. The other
     * difference the vocabulary draws between them — that an offset may also be a unitless `0` —
     * needs no code: that arm would return a zero displacement, and a null returned instead leaves
     * the caller applying no displacement at all, which is the same card in the same place.
     */
    private fun lengthPx(value: String, availablePx: Int, allowNegative: Boolean): Int? {
        val text = value.lowercase()
        val px = when {
            text.endsWith("px") -> text.dropLast(2).toFloatOrNull()?.times(density)
            text.endsWith("%") -> text.dropLast(1).toFloatOrNull()?.div(100f)?.times(availablePx)
            else -> null
        } ?: return null
        val rounded = px.roundToInt()
        // A size of zero or less is server-legal but would render an invisible card that has
        // already fired its impression — reject it here rather than let it reach a layout call.
        // Tested after rounding, not before: `0.1px` is a positive float that still becomes a
        // zero layout dimension, which is the same invisible card by a different route. Only a
        // size (`allowNegative == false`) is held to it; a negative offset is a displacement
        // toward the other edge and means something.
        return if (!allowNegative && rounded <= 0) null else rounded
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
        private const val DEFAULT_POPUP_WIDTH = 600f
        private const val DEFAULT_POPUP_RADIUS = 10f
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
        // the ones above: the card keys understand `center`, `displayPosition` never will. Each is
        // read on the one axis of the one banner type that reads the column today (the bar's
        // vertical, the flyout's horizontal), so adopting these keys does not widen its role.
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

/**
 * The popup's card: as wide as [wantedWidth] and, unless [wantedHeight] fixes it, as tall as its
 * content — the content-sized card sdk-swift draws (PR #23) and sdk-react-native adopted on
 * 2026-10-05, where this SDK used to fill the window.
 *
 * Both are capped [gutterPx] short of the box the card is placed in on each side, the content
 * scrolling inside past that, and the cap is never under [floorPx] (sdk-swift's and React Native's
 * 120). The box is not assumed from the display: it is the measure spec this view is handed, which
 * is its parent's own measured size less that parent's padding — the window less the system bars,
 * as the popup's root pads itself to the insets the window actually reports. An authored size is
 * capped the same way; it still wins over the content's own height.
 *
 * The content height is read from [contentSizer] alone — the popup's scroll view — rather than
 * from every child: with a body background image the scroller shares a wrapper with an
 * `ImageView`, and a photograph's intrinsic height must not decide how tall the card is.
 */
internal class PopupCardLayout(
    context: Context,
    private val wantedWidth: Int,
    private val wantedHeight: Int?,
    private val gutterPx: Int,
    private val floorPx: Int
) : FrameLayout(context) {

    var contentSizer: View? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(wantedWidth, cap(widthMeasureSpec))
        val maxHeight = cap(heightMeasureSpec)
        val height = wantedHeight?.let { minOf(it, maxHeight) } ?: run {
            val sizer = contentSizer ?: getChildAt(0) ?: return@run 0
            sizer.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            )
            sizer.measuredHeight
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    private fun cap(spec: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2
        else maxOf(MeasureSpec.getSize(spec) - 2 * gutterPx, floorPx)
}
