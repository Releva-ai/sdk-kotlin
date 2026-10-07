package ai.releva.sdk.ui.banner

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every string `DesignRenderer.parseColor` sees is CSS — it comes from a banner's `cssStyles` or
 * from the Unlayer design JSON — and `Color.parseColor`, which it used to delegate to, is not a CSS
 * parser. It throws on the three-digit form the admin serves as its own default, and it reads an
 * eight-digit value in Android's `#AARRGGBB` order where CSS puts the alpha LAST.
 *
 * Both of those were silent: the first dropped the colour and fell back, the second produced a
 * different, confidently wrong colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DesignRendererColorTest {

    @Test
    fun `three and four digit hex expand each digit`() {
        assertEquals(Color.BLACK, DesignRenderer.parseColor("#000"))
        assertEquals(Color.WHITE, DesignRenderer.parseColor("#fff"))
        assertEquals(Color.argb(255, 255, 0, 0), DesignRenderer.parseColor("#F00"))
        assertEquals(Color.argb(0x88, 0x11, 0x22, 0x33), DesignRenderer.parseColor("#1238"))
    }

    /**
     * The order that changes meaning. `#ff000080` is a half-transparent red in CSS; read as
     * `#AARRGGBB` it is an opaque navy, which is what this SDK drew for it until now — for design
     * colours as much as for `cssStyles`, since both come through here.
     */
    @Test
    fun `eight digit hex puts the alpha last, not first`() {
        assertEquals(Color.argb(0x80, 0xff, 0x00, 0x00), DesignRenderer.parseColor("#ff000080"))
        assertEquals(Color.argb(0xff, 0xff, 0xff, 0x00), DesignRenderer.parseColor("#ffff00ff"))
    }

    @Test
    fun `six digit hex is unchanged, trimmed and case-insensitive`() {
        assertEquals(Color.argb(255, 0xe0, 0x00, 0x00), DesignRenderer.parseColor("  #E00000 "))
    }

    @Test
    fun `rgb and rgba are read as css writes them`() {
        assertEquals(Color.argb(255, 0, 160, 0), DesignRenderer.parseColor("rgb(0, 160, 0)"))
        assertEquals(
            Color.argb(255, 255, 255, 255),
            DesignRenderer.parseColor("rgba(255, 255, 255, 1)")
        )
        assertEquals(Color.argb(127, 1, 2, 3), DesignRenderer.parseColor("rgba(1,2,3,0.5)"))
    }

    @Test
    fun `transparent is a colour`() {
        assertEquals(Color.TRANSPARENT, DesignRenderer.parseColor("TRANSPARENT"))
    }

    @Test
    fun `anything else is absent, so the caller keeps its own default`() {
        for (value in listOf(null, "", "   ", "#", "#12", "#12345", "#1234567", "#ggg", "red", "0")) {
            assertNull("$value", DesignRenderer.parseColor(value))
        }
    }
}
