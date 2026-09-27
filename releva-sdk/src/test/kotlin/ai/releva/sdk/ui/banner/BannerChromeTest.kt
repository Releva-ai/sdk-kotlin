package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The nine `cssStyles` keys that describe a banner's card — the chrome around the rendered design
 * — and where that card sits, as `BannerDisplayManager` honours them.
 *
 * What every group below pins first is the compatibility rule, because it is the whole design: a
 * banner whose author never opened these controls — which is every banner in production today —
 * must lay out exactly as it did before the keys existed, and so must one carrying all nine at
 * their documented defaults. Only then do the per-key tests say what an authored value moves.
 *
 * `qualifiers = "xxhdpi"` pins density to 3.0 for the same reason `DesignRendererPaddingTest` does:
 * at the default 1.0 a missing or doubled density multiply is invisible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "xxhdpi")
class BannerChromeTest {

    private lateinit var client: RelevaClient
    private var trackingServer: MockWebServer? = null
    private var host: ActivityController<BannerHostActivity>? = null
    private var tokenCounter = 0

    private val metrics get() = RuntimeEnvironment.getApplication().resources.displayMetrics
    private val density get() = metrics.density
    private val screenWidth get() = metrics.widthPixels

    /** Read the way `BannerDisplayManager` reads it, since that value is part of what is pinned. */
    private val statusBarHeight: Int
        get() {
            val res = RuntimeEnvironment.getApplication().resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }

    @Before
    fun setUp() {
        assertEquals("fixture assumes a 3x screen", 3.0f, density, 0.01f)
        BannerSessionStore.startNewSession()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200)
        }
        server.start()
        trackingServer = server
        client = RelevaClient(RuntimeEnvironment.getApplication(), "", "test-token").apply {
            setEndpointOverride(server.url("/").toString().trimEnd('/'))
        }
    }

    @After
    fun tearDown() {
        host?.close()
        trackingServer?.shutdown()
    }

    // ---- the compatibility rule ---------------------------------------------------------------

    @Test
    fun `a bar with none of the keys lays out as it did before they existed`() {
        val card = showBar(cssStyles = emptyMap())

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, card.params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, card.params.height)
        assertEquals(Gravity.TOP, card.verticalGravity)
        assertEquals(Color.TRANSPARENT, card.color)
        assertEquals((16 * density).toInt(), card.content.paddingLeft)
        assertEquals(statusBarHeight + (12 * density).toInt(), card.content.paddingTop)
        assertEquals((12 * density).toInt(), card.content.paddingBottom)
    }

    @Test
    fun `the nine keys at their documented defaults change nothing on a bar`() {
        val withoutKeys = showBar(cssStyles = emptyMap()).snapshot()
        val atDefaults = showBar(cssStyles = DOCUMENTED_DEFAULTS).snapshot()

        assertEquals(withoutKeys, atDefaults)
    }

    @Test
    fun `a popup with none of the keys lays out as it did before they existed`() {
        val card = showPopup(cssStyles = emptyMap())

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, card.params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, card.params.height)
        assertEquals(0, card.params.topMargin)
        assertEquals(0, card.params.leftMargin)
        assertEquals(Color.WHITE, card.color)
    }

    @Test
    fun `the nine keys at their documented defaults change nothing on a popup`() {
        val withoutKeys = showPopup(cssStyles = emptyMap()).snapshot()
        val atDefaults = showPopup(cssStyles = DOCUMENTED_DEFAULTS).snapshot()

        assertEquals(withoutKeys, atDefaults)
    }

    @Test
    fun `a flyout with none of the keys lays out as it did before they existed`() {
        val card = showFlyout(cssStyles = emptyMap(), displayPosition = "right")

        assertEquals((screenWidth * 0.8).toInt(), card.params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, card.params.height)
        assertEquals(Gravity.END, card.horizontalGravity)
        assertEquals(Color.WHITE, card.color)
        assertEquals(statusBarHeight, card.view.paddingTop)
        assertEquals(0, card.view.paddingLeft)
    }

    @Test
    fun `the nine keys at their documented defaults change nothing on a flyout`() {
        val withoutKeys = showFlyout(emptyMap(), displayPosition = "left").snapshot()
        val atDefaults = showFlyout(DOCUMENTED_DEFAULTS, displayPosition = "left").snapshot()

        assertEquals(withoutKeys, atDefaults)
    }

    /**
     * The reason this work exists. `popupBackgroundColor` is an Unlayer editor default written
     * wholesale into every design — our editor runs Unlayer in web display mode, which never shows
     * the Popup Builder that would author it — and the popup used to paint its card with it.
     * `cardBackgroundColor` owns the property now, so the editor's value no longer moves anything.
     */
    @Test
    fun `a popup no longer takes its card colour from the Unlayer design`() {
        val card = showPopup(
            cssStyles = emptyMap(),
            bodyValues = mapOf("popupBackgroundColor" to "#ff0000")
        )

        assertEquals(Color.WHITE, card.color)
    }

    // ---- one key at a time --------------------------------------------------------------------

    @Test
    fun `cardBackgroundColor paints the popup card`() {
        val card = showPopup(cssStyles = mapOf("cardBackgroundColor" to "#123456"))

        assertEquals(Color.parseColor("#123456"), card.color)
    }

    @Test
    fun `cardBackgroundColor paints a bar card that has no colour of its own`() {
        val card = showBar(cssStyles = mapOf("cardBackgroundColor" to "#123456"))

        assertEquals(Color.parseColor("#123456"), card.color)
    }

    @Test
    fun `cardWidth in percent resolves against the available width`() {
        val card = showBar(cssStyles = mapOf("cardWidth" to "50%"))

        assertEquals(screenWidth / 2, card.params.width)
    }

    @Test
    fun `cardHeight in px is scaled by the display density`() {
        val card = showFlyout(cssStyles = mapOf("cardHeight" to "100px"), displayPosition = "right")

        assertEquals(300, card.params.height)
    }

    @Test
    fun `cardBorderRadius is a unitless css pixel value`() {
        val card = showPopup(cssStyles = mapOf("cardBorderRadius" to "12"))

        assertEquals(36f, (card.view.background as GradientDrawable).cornerRadius, 0.01f)
    }

    /**
     * A `GradientDrawable` corner radius only rounds its own painting, not the card's children —
     * a design with a body or row background colour would still square off the corners it just
     * rounded. `clipToOutline` is what actually clips descendants to the rounded rect.
     */
    @Test
    fun `cardBorderRadius clips the card's children to the rounded corners`() {
        val card = showPopup(cssStyles = mapOf("cardBorderRadius" to "12"))

        assertEquals(true, card.view.clipToOutline)
    }

    @Test
    fun `no cardBorderRadius leaves clipToOutline off`() {
        val card = showPopup(cssStyles = emptyMap())

        assertEquals(false, card.view.clipToOutline)
    }

    /**
     * `DesignRenderer.render`'s `maxWidthPx` has to be the card's own width, not the screen's — a
     * design's `contentWidth` is coerced against it, and a card narrower than the screen otherwise
     * lets the design ask for more room than the card actually has, overflowing and clipping under
     * a `ScrollView` that does not scroll horizontally. `cardWidth` is computed before the render
     * call precisely so it can be threaded through as `maxWidthPx`.
     */
    @Test
    fun `an authored cardWidth reaches the design's own contentWidth ceiling`() {
        val card = showPopup(
            cssStyles = mapOf("cardWidth" to "50%"),
            bodyValues = mapOf("contentWidth" to "5000px")
        )
        val innerLayout = (card.content as ViewGroup).getChildAt(0)

        assertEquals(screenWidth / 2, innerLayout.layoutParams.width)
    }

    /**
     * The `GradientDrawable`/`clipToOutline` chrome has to land on `bgWrapper`, not the inner
     * `flyoutContainer`: with a body background image, `bgWrapper` is the view that is actually
     * the card (the image and `flyoutContainer` are siblings inside it), so an authored
     * `cardBorderRadius` only clips visibly if it clips the image along with the close button and
     * scroll content, not just the latter two.
     */
    @Test
    fun `cardBorderRadius clips a flyout's background image to the rounded corners`() {
        show(banner(
            "flyout", mapOf("cardBorderRadius" to "12"), displayPosition = "right",
            bodyValues = mapOf("backgroundImage" to mapOf("url" to "https://example.invalid/bg.png"))
        ))
        val bgWrapper = dialogCard()

        assertEquals(true, bgWrapper.clipToOutline)
        assertEquals(36f, (bgWrapper.background as GradientDrawable).cornerRadius, 0.01f)
    }

    @Test
    fun `no cardBorderRadius leaves a flyout's background image clipToOutline off`() {
        show(banner(
            "flyout", emptyMap(), displayPosition = "right",
            bodyValues = mapOf("backgroundImage" to mapOf("url" to "https://example.invalid/bg.png"))
        ))
        val bgWrapper = dialogCard()

        assertEquals(false, bgWrapper.clipToOutline)
    }

    /**
     * `cardOffsetHorizontal` insets the flyout from the edge it is anchored to — a margin on that
     * edge — rather than padding the card's own content area, which would shrink it below the
     * width the content was just rendered to fit (`flyoutWidth`) and clip the overflow.
     */
    @Test
    fun `cardOffsetHorizontal moves a flyout's card instead of shrinking its content area`() {
        val card = showFlyout(
            cssStyles = mapOf("cardOffsetHorizontal" to "10px"),
            displayPosition = "right"
        )

        assertEquals(30, card.params.rightMargin)
        assertEquals(0, card.params.leftMargin)
        assertEquals(0, card.view.paddingLeft)
        assertEquals(0, card.view.paddingRight)
    }

    // ---- the popup's window chrome, which an authored card size must not take away -------------

    /**
     * Holding the design clear of the status bar, the cutout and the gesture pill is the window's
     * business, not the card's, and an authored size does not change that: the inset is applied
     * the same way for every card, as it was before these keys existed. A card the author has
     * sized and centred carries clearance it does not need, which costs it some empty space; the
     * alternative — deciding per edge whether the card still reaches it — errs the other way, and
     * that way lies content underneath a system bar.
     */
    // Per-type window insets (`WindowInsetsCompat.Builder.setInsets`) only round-trip faithfully
    // through a real platform `android.view.WindowInsets` from API 30 on, which is what backs
    // this test's `dispatchInsets` call; below that, androidx's compat shim collapses everything
    // back to one legacy `systemWindowInsets` value and the per-axis assertions would not be
    // exercising what they claim to.
    @Config(sdk = [30])
    @Test
    fun `a popup holds its content clear of the system bars whatever size its card is`() {
        for (cssStyles in listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("cardWidth" to "50%"),
            mapOf("cardWidth" to "50%", "cardHeight" to "50%")
        )) {
            val card = showPopup(cssStyles = cssStyles)
            val scrollView = (card.view as ViewGroup).getChildAt(0) as ScrollView
            val closeButton = popupCloseButton()

            dispatchInsets(card.view, top = 100, left = 20, right = 30, bottom = 40)

            val expectedTop = maxOf(100, statusBarHeight)
            assertEquals("$cssStyles", expectedTop, scrollView.paddingTop)
            assertEquals("$cssStyles", 20, scrollView.paddingLeft)
            assertEquals("$cssStyles", 30, scrollView.paddingRight)
            assertEquals("$cssStyles", 40, scrollView.paddingBottom)

            val lp = closeButton.layoutParams as FrameLayout.LayoutParams
            assertEquals("$cssStyles", expectedTop + (8 * density).toInt(), lp.topMargin)
            assertEquals("$cssStyles", 30 + (8 * density).toInt(), lp.rightMargin)
        }
    }

    /**
     * The popup dialog is not cancelable and does not dismiss on a touch outside, so its close
     * button is the only way out of it. That was safe while the card was always the whole window;
     * it is not once `cardWidth` can make the card narrower than the button's own box, because
     * `ViewGroup.dispatchTouchEvent` only forwards a pointer to a child the pointer falls inside,
     * so the part of the button hanging outside the card would not be tappable — on a modal that
     * blocks the screen and is re-shown after a rotation. Keeping the button on the window keeps
     * it reachable at any card size.
     */
    @Test
    fun `a popup's close button is on the window and not inside the card`() {
        val card = showPopup(cssStyles = mapOf("cardWidth" to "10px", "cardHeight" to "10px"))
        val closeButton = popupCloseButton()

        assertEquals("the card must be smaller than the button for this to mean anything", 30, card.params.width)
        assertEquals(-1, (card.view as ViewGroup).indexOfChild(closeButton))
        assertEquals(
            Gravity.TOP or Gravity.END,
            (closeButton.layoutParams as FrameLayout.LayoutParams).gravity
        )

        closeButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, ShadowDialog.getLatestDialog().isShowing)
    }

    /**
     * `cardOffsetHorizontal` is a margin on a card that still spans the window, and
     * `FrameLayout.measureChildWithMargins` subtracts both margins from such a child's width — so
     * the card really is narrower than the screen, and the design's own `contentWidth` has to be
     * coerced against *that*. Coerced against the screen instead, the design is laid out wider
     * than the card, centred, and clipped on both sides under a `ScrollView` that does not scroll
     * sideways.
     */
    @Test
    fun `an authored cardOffsetHorizontal reaches the design's own contentWidth ceiling`() {
        val card = showPopup(
            cssStyles = mapOf("cardOffsetHorizontal" to "40px"),
            bodyValues = mapOf("contentWidth" to "5000px")
        )
        val innerLayout = (card.content as ViewGroup).getChildAt(0)

        assertEquals(120, card.params.leftMargin)
        assertEquals(120, card.params.rightMargin)
        assertEquals(screenWidth - 240, innerLayout.layoutParams.width)
    }

    // ---- contentVerticalAlign -----------------------------------------------------------------

    @Test
    fun `contentVerticalAlign places the content inside the bar card`() {
        val aligned = showBar(cssStyles = mapOf("contentVerticalAlign" to "bottom"))
        assertEquals(Gravity.BOTTOM, aligned.contentVerticalGravity)

        val default = showBar(cssStyles = emptyMap())
        assertEquals(Gravity.TOP, default.contentVerticalGravity)
    }

    /**
     * The contract's whole statement about this key: it is observable only when the card is taller
     * than its content. Laid out in a viewport three times the design's own height, `center` puts
     * the design halfway down and `top` — the default — leaves it where it has always been.
     */
    @Test
    fun `contentVerticalAlign centres a design shorter than the popup card`() {
        val scroller = popupScroller(cssStyles = mapOf("contentVerticalAlign" to "center"))
        val naturalHeight = scroller.measureContentHeight()

        scroller.layoutAt(height = naturalHeight * 3)

        assertEquals(naturalHeight, scroller.design.height)
        assertEquals(naturalHeight, scroller.designTop)

        val atDefault = popupScroller(cssStyles = emptyMap())
        atDefault.layoutAt(height = atDefault.measureContentHeight() * 3)
        assertEquals(0, atDefault.designTop)
    }

    /**
     * And the other side of "only when the card is taller than its content": with a design taller
     * than the card, every alignment has to leave it exactly where `top` puts it. Giving the
     * design the gravity directly would lay it out at a negative top, and
     * `ScrollView.getScrollRange()` is derived from its child's height alone and never its offset
     * — so everything above y = 0 would be content that no scroll position could reach.
     */
    @Test
    fun `contentVerticalAlign never lifts a design taller than the popup card above the scroll range`() {
        for (align in listOf("center", "bottom")) {
            val scroller = popupScroller(cssStyles = mapOf("contentVerticalAlign" to align))
            val naturalHeight = scroller.measureContentHeight()
            assertTrue("the fixture design must have a height of its own", naturalHeight > 1)

            // One pixel of card: any design at all is taller than that.
            scroller.layoutAt(height = 1)

            assertEquals("$align must not lift the design above the top", 0, scroller.designTop)
            assertEquals("$align must not shorten the design", naturalHeight, scroller.design.height)
        }
    }

    /** The same, for the flyout, whose scroll view fills its viewport only once aligned. */
    @Test
    fun `contentVerticalAlign never lifts a design taller than the flyout card above the scroll range`() {
        val scroller = flyoutScroller(cssStyles = mapOf("contentVerticalAlign" to "center"))
        val naturalHeight = scroller.measureContentHeight()
        assertTrue("the fixture design must have a height of its own", naturalHeight > 1)

        scroller.layoutAt(height = 1)

        assertEquals(0, scroller.designTop)
        assertEquals(naturalHeight, scroller.design.height)
    }

    @Test
    fun `cardPositionVertical overrides the displayPosition a bar falls back to`() {
        val card = showBar(mapOf("cardPositionVertical" to "top"), displayPosition = "bottom")

        assertEquals(Gravity.TOP, card.verticalGravity)
    }

    @Test
    fun `cardPositionHorizontal overrides the displayPosition a flyout falls back to`() {
        val card = showFlyout(mapOf("cardPositionHorizontal" to "left"), displayPosition = "right")

        assertEquals(Gravity.START, card.horizontalGravity)
    }

    @Test
    fun `cardOffsetVertical and cardOffsetHorizontal replace the bar's paddings`() {
        val card = showBar(
            cssStyles = mapOf("cardOffsetVertical" to "20px", "cardOffsetHorizontal" to "10px")
        )

        assertEquals(30, card.content.paddingLeft)
        assertEquals(30, card.content.paddingRight)
        assertEquals(statusBarHeight + 60, card.content.paddingTop)
        assertEquals(60, card.content.paddingBottom)
    }

    @Test
    fun `an offset may be negative and a size may not`() {
        val offset = showPopup(cssStyles = mapOf("cardOffsetVertical" to "-10px"))
        assertEquals(-30, offset.params.topMargin)

        val size = showBar(cssStyles = mapOf("cardWidth" to "-50px"))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, size.params.width)
    }

    @Test
    fun `a bare zero is an offset but not a size`() {
        val offset = showBar(cssStyles = mapOf("cardOffsetHorizontal" to "0"))
        assertEquals(0, offset.content.paddingLeft)

        val size = showBar(cssStyles = mapOf("cardWidth" to "0"))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, size.params.width)
    }

    /**
     * And neither is a size that only rounds away to nothing: `0.1px` is a positive length that
     * still becomes a zero layout dimension, which is the same invisible card — already counted
     * as an impression, never seen — that a literal zero is rejected to avoid.
     */
    @Test
    fun `a size that rounds away to nothing is not a size`() {
        val card = showBar(cssStyles = mapOf("cardWidth" to "0.1px"))

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, card.params.width)
    }

    // ---- values that must never reach a layout call --------------------------------------------

    @Test
    fun `a displayPosition a bar cannot use lands on the documented default`() {
        assertEquals(Gravity.TOP, showBar(emptyMap(), displayPosition = null).verticalGravity)
        assertEquals(Gravity.TOP, showBar(emptyMap(), displayPosition = "left").verticalGravity)
        assertEquals(Gravity.BOTTOM, showBar(emptyMap(), displayPosition = "bottom").verticalGravity)
    }

    @Test
    fun `a displayPosition a flyout cannot use lands on the documented default`() {
        assertEquals(Gravity.END, showFlyout(emptyMap(), displayPosition = null).horizontalGravity)
        assertEquals(Gravity.END, showFlyout(emptyMap(), displayPosition = "top").horizontalGravity)
        assertEquals(Gravity.START, showFlyout(emptyMap(), displayPosition = "left").horizontalGravity)
    }

    /**
     * `displayPosition` drives exactly two axes in this SDK — the bar's vertical (`isBottom`) and
     * the flyout's horizontal (`isLeft`) — and `auto` means "what this SDK does today", so
     * adopting `cardPositionVertical`/`Horizontal` must not start reading the column anywhere it
     * was not read before. The popup is the whole of the third type: it has never consulted
     * `displayPosition` on either axis, and a row carrying a stale one must still centre.
     *
     * Each case authors the size that makes gravity observable at all, since that is the only way
     * a widened fallback could ever be seen.
     */
    @Test
    fun `a popup places its card without ever reading displayPosition`() {
        for (position in listOf("left", "right", "top", "bottom")) {
            val card = showPopup(
                cssStyles = mapOf("cardWidth" to "50%", "cardHeight" to "50%"),
                displayPosition = position
            )

            assertEquals("$position must not move a popup", Gravity.CENTER_VERTICAL, card.verticalGravity)
            assertEquals(
                "$position must not move a popup",
                Gravity.CENTER_HORIZONTAL,
                card.horizontalGravity
            )
        }
    }

    /** The bar reads `displayPosition` on its vertical axis only; its horizontal stays centred. */
    @Test
    fun `a bar places its card horizontally without reading displayPosition`() {
        val card = showBar(mapOf("cardWidth" to "50%"), displayPosition = "left")

        assertEquals(Gravity.CENTER_HORIZONTAL, card.horizontalGravity)
        assertEquals(Gravity.TOP, card.verticalGravity)
    }

    /** And the flyout reads it on its horizontal axis only; its vertical stays at the top. */
    @Test
    fun `a flyout places its card vertically without reading displayPosition`() {
        val card = showFlyout(mapOf("cardHeight" to "50%"), displayPosition = "bottom")

        assertEquals(Gravity.TOP, card.verticalGravity)
        assertEquals(Gravity.END, card.horizontalGravity)
    }

    @Test
    fun `an unparseable length falls back to the default`() {
        for (value in listOf("80vw", "calc(100% - 20px)", "", "px", "wide")) {
            val card = showBar(cssStyles = mapOf("cardWidth" to value))
            assertEquals(
                "cardWidth=$value should have changed nothing",
                ViewGroup.LayoutParams.MATCH_PARENT,
                card.params.width
            )
        }
    }

    @Test
    fun `an unparseable position falls back to the default`() {
        val card = showBar(mapOf("cardPositionVertical" to "middle"), displayPosition = "bottom")

        assertEquals(Gravity.BOTTOM, card.verticalGravity)
    }

    /**
     * The defaults are literals produced in another repo, so a case near-miss or stray whitespace
     * has to read as "untouched" rather than as intent — here by still deferring to
     * `displayPosition` instead of taking ` AUTO ` for an alignment it cannot resolve.
     */
    @Test
    fun `a default that differs only in case or whitespace is still a default`() {
        val card = showBar(mapOf("cardPositionVertical" to " AUTO "), displayPosition = "bottom")

        assertEquals(Gravity.BOTTOM, card.verticalGravity)
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * A rendered banner's card and the view inside it that `contentVerticalAlign` places — which
     * for a bar is also the view carrying the offsets.
     */
    private class Card(val view: View, val content: View) {
        val params get() = view.layoutParams as FrameLayout.LayoutParams

        /** Null where the view carries no flat colour, which is what a bar's card carried. */
        val color get() = (view.background as? ColorDrawable)?.color

        val verticalGravity get() = params.gravity and Gravity.VERTICAL_GRAVITY_MASK

        /** `Gravity.START` and `END` carry a relative-direction bit above the horizontal ones. */
        val horizontalGravity get() = params.gravity and (Gravity.START or Gravity.END)

        val contentVerticalGravity
            get() = (content.layoutParams as FrameLayout.LayoutParams).gravity and
                Gravity.VERTICAL_GRAVITY_MASK

        /** Everything the compatibility rule promises is unchanged. */
        fun snapshot() = listOf(
            params.width, params.height, params.gravity,
            params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin,
            color, view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom,
            content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom,
            (content.layoutParams as FrameLayout.LayoutParams).gravity
        )
    }

    /** The bar's card, which the manager adds as an overlay beside the wrapper it built. */
    private fun showBar(cssStyles: Map<String, Any?>, displayPosition: String? = null): Card {
        val activity = show(banner("bar", cssStyles, displayPosition))
        val outer = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        assertEquals("expected the content wrapper plus one bar overlay", 2, outer.childCount)
        val bar = outer.getChildAt(1) as ViewGroup
        return Card(bar, bar.getChildAt(0))
    }

    /** The popup's card: the first child of the dialog's root layout, holding a scrolled design. */
    private fun showPopup(
        cssStyles: Map<String, Any?>,
        bodyValues: Map<String, Any?> = emptyMap(),
        displayPosition: String? = null
    ): Card {
        show(banner("popup", cssStyles, displayPosition, bodyValues = bodyValues))
        val popup = dialogCard()
        return Card(popup, (popup.getChildAt(0) as ScrollView).getChildAt(0))
    }

    /** The flyout's card: the dialog's overlay holds it, and its second child is the scroller. */
    private fun showFlyout(cssStyles: Map<String, Any?>, displayPosition: String?): Card {
        show(banner("flyout", cssStyles, displayPosition))
        val flyout = dialogCard()
        return Card(flyout, (flyout.getChildAt(1) as ScrollView).getChildAt(0))
    }

    /**
     * A banner's scroll view, driven through a real measure and layout pass so that where the
     * design actually lands can be read rather than inferred from `LayoutParams`. An authored
     * `contentVerticalAlign` puts an alignment wrapper between the scroller and the design, which
     * is what [aligned] says; without one the design is the scroller's own child, as on master.
     */
    private class Scroller(val view: ScrollView, val width: Int, private val aligned: Boolean) {
        val design: View
            get() = if (aligned) (view.getChildAt(0) as ViewGroup).getChildAt(0)
            else view.getChildAt(0)

        /** The design's top in the scroller's own content space, wrapper offset included. */
        val designTop: Int
            get() = design.top + if (aligned) view.getChildAt(0).top else 0

        /** The height the design asks for when nothing constrains it — what a ScrollView gives it. */
        fun measureContentHeight(): Int {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return design.measuredHeight
        }

        fun layoutAt(height: Int) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
        }
    }

    private fun popupScroller(cssStyles: Map<String, Any?>): Scroller {
        show(banner("popup", cssStyles, displayPosition = null))
        return Scroller(
            dialogCard().getChildAt(0) as ScrollView,
            screenWidth,
            aligned = cssStyles.containsKey("contentVerticalAlign")
        )
    }

    private fun flyoutScroller(cssStyles: Map<String, Any?>): Scroller {
        show(banner("flyout", cssStyles, displayPosition = "right"))
        return Scroller(
            dialogCard().getChildAt(1) as ScrollView,
            (screenWidth * 0.8).toInt(),
            aligned = cssStyles.containsKey("contentVerticalAlign")
        )
    }

    /**
     * Simulates the platform delivering window insets to [view], the same way an
     * `OnApplyWindowInsetsListener` registered on it would receive them from a real window. The
     * four values are raw pixels, as `WindowInsetsCompat` reports them, not `dp`.
     */
    private fun dispatchInsets(view: View, top: Int, left: Int, right: Int, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(left, top, right, bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun dialogRoot(): ViewGroup {
        val content = ShadowDialog.getLatestDialog().findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) as ViewGroup
    }

    private fun dialogCard(): ViewGroup = dialogRoot().getChildAt(0) as ViewGroup

    /**
     * The popup's close button: a sibling of the card on the dialog's root, added after it so it
     * still draws above everything, rather than a child of the card as it was when the card was
     * unconditionally the whole window.
     */
    private fun popupCloseButton(): View {
        val root = dialogRoot()
        return root.getChildAt(root.childCount - 1)
    }

    /**
     * Renders [banner] on a host of its own. `bannerFlow` is process-wide, so the previous host is
     * closed first: a manager still attached would put a second copy of this banner on its screen
     * and leave the assertions reading the wrong one.
     */
    private fun show(banner: BannerResponse): AppCompatActivity {
        host?.close()
        val controller = Robolectric.buildActivity(BannerHostActivity::class.java)
        host = controller
        val activity = controller.setup().get()
        BannerDisplayManager(client, TARGET_SELECTOR) {}.attach(activity)
        BannerDisplayController.showBanner(banner)
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun banner(
        displayType: String,
        cssStyles: Map<String, Any?>,
        displayPosition: String?,
        bodyValues: Map<String, Any?> = emptyMap()
    ) = BannerResponse(
        token = "chrome-$displayType-${tokenCounter++}",
        displayType = displayType,
        cssSelector = TARGET_SELECTOR,
        displayPosition = displayPosition,
        cssStyles = cssStyles,
        design = designWithText(bodyValues)
    )

    private fun designWithText(bodyValues: Map<String, Any?>): Map<String, Any?> = mapOf(
        "body" to mapOf(
            "rows" to listOf(
                mapOf(
                    "columns" to listOf(
                        mapOf(
                            "contents" to listOf(
                                mapOf(
                                    "type" to "text",
                                    "values" to mapOf("text" to "<p>Chrome</p>")
                                )
                            )
                        )
                    ),
                    "values" to emptyMap<String, Any?>()
                )
            ),
            "values" to bodyValues
        )
    )

    private companion object {
        const val TARGET_SELECTOR = "#home-content"

        /** All nine keys, each carrying the value documented as its default. */
        val DOCUMENTED_DEFAULTS = mapOf<String, Any?>(
            "cardBackgroundColor" to "#fefefe",
            "cardWidth" to "auto",
            "cardHeight" to "auto",
            "cardBorderRadius" to "0",
            "contentVerticalAlign" to "top",
            "cardPositionVertical" to "auto",
            "cardPositionHorizontal" to "auto",
            "cardOffsetVertical" to "auto",
            "cardOffsetHorizontal" to "auto"
        )
    }
}
