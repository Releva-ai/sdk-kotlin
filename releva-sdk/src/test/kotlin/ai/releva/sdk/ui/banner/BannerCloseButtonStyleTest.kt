package ai.releva.sdk.ui.banner

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The five `cssStyles` keys that describe a banner's close control, as the contract all four mobile
 * SDKs implement states them. The inputs below are the ones the brief lists for the rows
 * `QA-CLS-01`…`QA-CLS-10` (section R).
 *
 * The compatibility statement they start from is that there isn't one: the API fills every missing
 * key, so every banner in production carries `#000` on `#fff` at radius 20 and size 14 with a blank
 * border, and this SDK used to draw a dark-grey ✕ on white inside a hard-coded light-grey ring.
 * `CLS-01` is the row that pins the new reading of those same values, which is what the web shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BannerCloseButtonStyleTest {

    /** CLS-01: no keys at all — and the defaults every other row is measured against. */
    @Test
    fun `an unauthored control is a black glyph on a white circle with no border`() {
        val style = BannerCloseButtonStyle.of(emptyMap())

        assertEquals(Color.BLACK, style.iconColor)
        assertEquals(Color.WHITE, style.backgroundColor)
        assertEquals(0f, style.borderWidthDp, 0f)
        assertEquals(Color.TRANSPARENT, style.borderColor)
        assertEquals(32, style.sideDp)
        assertEquals(48, style.tapTargetDp)
        assertEquals(20, style.glyphSizeDp)
        // 20 capped at half the 32dp side, which is still a circle.
        assertEquals(16f, style.cornerRadiusDp, 0f)
    }

    /** CLS-02, and the three-digit form the admin serves as its own default. */
    @Test
    fun `three digit hex colours the glyph and the fill`() {
        val style = BannerCloseButtonStyle.of(
            mapOf("closeButtonColor" to "#f00", "closeButtonBackgroundColor" to "#ff0")
        )

        assertEquals(Color.argb(255, 255, 0, 0), style.iconColor)
        assertEquals(Color.argb(255, 255, 255, 0), style.backgroundColor)
    }

    /**
     * CLS-03. The eight-digit form is where reading Android's `#AARRGGBB` instead of CSS's
     * alpha-last produced a confidently wrong colour: yellow was drawn as magenta and a
     * half-transparent red as an opaque navy.
     */
    @Test
    fun `eight digit hex is read alpha-last`() {
        val style = BannerCloseButtonStyle.of(
            mapOf(
                "closeButtonColor" to "#ffff00ff",
                "closeButtonBackgroundColor" to "#ff000080"
            )
        )

        assertEquals(Color.argb(0xff, 0xff, 0xff, 0x00), style.iconColor)
        assertEquals(Color.argb(0x80, 0xff, 0x00, 0x00), style.backgroundColor)
    }

    /** CLS-04: the CSS shorthand, which used to be handed whole to a colour parser and lost. */
    @Test
    fun `the border shorthand yields a width and a colour`() {
        val style = BannerCloseButtonStyle.of(mapOf("closeButtonBorder" to "2px solid #e00000"))

        assertEquals(2f, style.borderWidthDp, 0f)
        assertEquals(Color.argb(255, 0xe0, 0x00, 0x00), style.borderColor)
    }

    @Test
    fun `a border colour with no width is one pixel, and rgb is a colour like any other`() {
        val style = BannerCloseButtonStyle.of(mapOf("closeButtonBorder" to "solid rgb(1, 2, 3)"))

        assertEquals(1f, style.borderWidthDp, 0f)
        assertEquals(Color.argb(255, 1, 2, 3), style.borderColor)
    }

    /** The web draws no border by default, so everything that names no colour means none. */
    @Test
    fun `a border naming no colour is no border`() {
        for (value in listOf("", "   ", "none", "0", "1px solid", "thin dotted", "1px none #fff", "1px solid #ffffffffff")) {
            val style = BannerCloseButtonStyle.of(mapOf("closeButtonBorder" to value))
            assertEquals("closeButtonBorder=$value", 0f, style.borderWidthDp, 0f)
            assertEquals("closeButtonBorder=$value", Color.TRANSPARENT, style.borderColor)
        }
    }

    /** CLS-05, and the clamp on either side of it. */
    @Test
    fun `closeFontSize sizes the button and is clamped to 8 and 26`() {
        val large = BannerCloseButtonStyle.of(mapOf("closeFontSize" to "24"))
        assertEquals(42, large.sideDp)
        assertEquals(30, large.glyphSizeDp)
        assertEquals(48, large.tapTargetDp)

        // Above the ceiling: 26, so a 44dp square — still inside the 48dp tap target.
        val huge = BannerCloseButtonStyle.of(mapOf("closeFontSize" to "40px"))
        assertEquals(44, huge.sideDp)
        assertEquals(48, huge.tapTargetDp)

        // Below the floor: 8, and the square stays at its own 32dp minimum.
        val tiny = BannerCloseButtonStyle.of(mapOf("closeFontSize" to "2"))
        assertEquals(32, tiny.sideDp)
        assertEquals(14, tiny.glyphSizeDp)
    }

    /** CLS-06: a square button, and the two functional colour forms. */
    @Test
    fun `a zero border radius squares the button`() {
        val style = BannerCloseButtonStyle.of(
            mapOf(
                "closeButtonBorderRadius" to "0",
                "closeButtonColor" to "rgba(255, 255, 255, 1)",
                "closeButtonBackgroundColor" to "rgb(0, 160, 0)"
            )
        )

        assertEquals(0f, style.cornerRadiusDp, 0f)
        assertEquals(Color.WHITE, style.iconColor)
        assertEquals(Color.argb(255, 0, 160, 0), style.backgroundColor)
    }

    /**
     * The radius is a corner, not a crop: anything past half the side would round the square into
     * the circle it already is at the default, so it is capped there rather than left to the
     * platform.
     */
    @Test
    fun `the border radius is capped at half the button's side`() {
        val style = BannerCloseButtonStyle.of(
            mapOf("closeButtonBorderRadius" to "999", "closeFontSize" to "24")
        )

        assertEquals(21f, style.cornerRadiusDp, 0f)
    }

    /**
     * CLS-07. These six keys are the web SDK's own CSS and have no mobile reading — the control
     * keeps this SDK's glyph and the placement its call site gives it — so a banner carrying all
     * of them at extremes must resolve to exactly the unauthored control.
     */
    @Test
    fun `the six web-only keys change nothing`() {
        val style = BannerCloseButtonStyle.of(
            mapOf(
                "closeButtonSymbol" to "✖",
                "closeButtonPadding" to "40px",
                "closeButtonFontWeight" to "900",
                "closeButtonLineHeight" to "3",
                "closeButtonTopPosition" to "-100px",
                "closeButtonRightPosition" to "-100px"
            )
        )

        assertEquals(BannerCloseButtonStyle.of(emptyMap()), style)
    }

    @Test
    fun `a blank or unparseable value falls back to the default`() {
        for (value in listOf("", "   ", "wheat", "calc(1px)")) {
            val style = BannerCloseButtonStyle.of(
                mapOf(
                    "closeButtonColor" to value,
                    "closeButtonBackgroundColor" to value,
                    "closeFontSize" to value,
                    "closeButtonBorderRadius" to value,
                    "closeButtonBorder" to value
                )
            )
            assertEquals(value, BannerCloseButtonStyle.of(emptyMap()), style)
        }
    }
}
