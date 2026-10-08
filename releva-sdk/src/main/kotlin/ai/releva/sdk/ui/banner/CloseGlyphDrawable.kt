package ai.releva.sdk.ui.banner

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The close control's ✕, drawn in exactly [color].
 *
 * It replaces `android.R.drawable.ic_menu_close_clear_cancel`, whose pixels are themselves
 * semi-transparent: tinted with `SRC_IN` the tint inherits that alpha, so the admin default
 * `#000` rendered as #666 grey and every other `closeButtonColor` at roughly 60% strength
 * (found on device, QA row CLS-01). Two round-capped strokes corner to corner of the bounds,
 * inset by half a stroke, so the glyph fills the box the caller sized to `closeFontSize`.
 */
internal class CloseGlyphDrawable(glyphColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The colour the strokes are drawn in, alpha included. */
    val color: Int get() = paint.color

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        if (side <= 0f) return
        val stroke = side * STROKE_FRACTION
        paint.strokeWidth = stroke
        val inset = stroke / 2f
        val left = b.exactCenterX() - side / 2f + inset
        val top = b.exactCenterY() - side / 2f + inset
        val right = b.exactCenterX() + side / 2f - inset
        val bottom = b.exactCenterY() + side / 2f - inset
        canvas.drawLine(left, top, right, bottom, paint)
        canvas.drawLine(right, top, left, bottom, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    internal companion object {
        /** Stroke weight as a share of the glyph's side. */
        const val STROKE_FRACTION = 0.14f
    }
}
