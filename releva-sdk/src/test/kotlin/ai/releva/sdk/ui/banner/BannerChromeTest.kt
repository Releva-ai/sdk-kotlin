package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
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
import kotlin.math.roundToInt

/**
 * The nine `cssStyles` keys that describe a banner's card — the chrome around the rendered design
 * — and where that card sits, as `BannerDisplayManager` honours them.
 *
 * What every group below pins first is the compatibility rule, because it is the whole design: a
 * banner whose author never opened these controls — which is every banner in production today —
 * must lay out exactly as it did before the keys existed, and so must one carrying all nine at
 * their documented defaults. Only then do the per-key tests say what an authored value moves.
 *
 * The close control is the card's neighbour rather than one of those keys, and has a resolver and a
 * suite of its own — what it is asserted for here is where it lands on the card and that each
 * display type paints what that resolver resolved.
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
    private val screenHeight get() = metrics.heightPixels

    /**
     * The close control at the default `closeFontSize`, in this fixture's pixels: a 32dp painted
     * square inside a 48dp tap target, placed 8dp in from the card's corner. `BannerCloseButtonStyle`
     * owns these numbers and `BannerCloseButtonStyleTest` pins them; here they are only what the
     * card's own geometry is measured against.
     */
    private val closeSidePx get() = (32 * density).toInt()
    private val closeTapTargetPx get() = (48 * density).toInt()
    private val closeRingPx get() = (closeTapTargetPx - closeSidePx) / 2
    private val closeMarginPx get() = (8 * density).toInt()

    /** The popup's content starts below the control: its margin, its painted side, the margin again. */
    private val closeBandPx get() = closeMarginPx * 2 + closeSidePx

    /** How far short of the box it is placed in the popup card is capped, on each side. */
    private val gutterPx get() = (16 * density).roundToInt()

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
        // The status-bar clearance moved from the content's padding to the CARD's top margin, so
        // the card's own background stops short of the clock instead of running up behind it.
        // The content does not move: it was at statusBarPad + 12 inside a card at y=0 and is now
        // at 12 inside a card at y=statusBarPad, which is what the next two assertions pin — a
        // default bar has no background to see, so nothing it DRAWS changes.
        assertEquals((12 * density).toInt(), card.content.paddingTop)
        assertEquals(statusBarHeight, card.params.topMargin)
        assertEquals(
            "the content's absolute top is unchanged",
            statusBarHeight + (12 * density).toInt(),
            card.params.topMargin + card.content.paddingTop
        )
        assertEquals((12 * density).toInt(), card.content.paddingBottom)
    }

    @Test
    fun `the nine keys at their documented defaults change nothing on a bar`() {
        val withoutKeys = showBar(cssStyles = emptyMap()).snapshot()
        val atDefaults = showBar(cssStyles = DOCUMENTED_DEFAULTS).snapshot()

        assertEquals(withoutKeys, atDefaults)
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
     * sdk-swift's order, adopted with the content-sized card: the authored `cardBackgroundColor`,
     * then the design's own `popupBackgroundColor`, then white. The design key had been dropped as
     * an editor default (#FFFFFF on every production banner that carries it); it is back as the
     * fallback so the SDKs read the same keys in the same order.
     */
    @Test
    fun `a popup's card colour falls back to the design's popupBackgroundColor`() {
        val fromDesign = showPopup(
            cssStyles = emptyMap(),
            bodyValues = mapOf("popupBackgroundColor" to "#ff0000")
        )
        assertEquals(Color.RED, fromDesign.color)

        val authored = showPopup(
            cssStyles = mapOf("cardBackgroundColor" to "#123456"),
            bodyValues = mapOf("popupBackgroundColor" to "#ff0000")
        )
        assertEquals(Color.parseColor("#123456"), authored.color)
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

    /** The bar's card stays flat and unclipped until a radius is authored; the popup is always rounded. */
    @Test
    fun `no cardBorderRadius leaves a bar's clipToOutline off`() {
        val card = showBar(cssStyles = emptyMap())

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

    // ---- the offsets, which are a translation --------------------------------------------------

    /**
     * The case the offsets exist as a translation for, and the one a symmetric margin gets wrong.
     * Both of the popup's axes are centred, and `FrameLayout` centres a child's *margin box* —
     * `childTop = … + topMargin - bottomMargin` — so equal margins on the two edges of an axis
     * cancel exactly and an author who centres a card and asks for `cardOffsetVertical: '40px'`
     * gets nothing at all. Read after a real measure and layout pass, because that cancellation is
     * invisible in the `LayoutParams` themselves: the margins are there, they just do not move it.
     */
    @Test
    fun `an offset displaces a centred card instead of cancelling out`() {
        val sized = mapOf<String, Any?>("cardWidth" to "50%", "cardHeight" to "50%")
        val baseline = showPopup(cssStyles = sized)
        layoutDialog()
        val baseX = baseline.view.x
        val baseY = baseline.view.y

        val moved = showPopup(cssStyles = sized + mapOf(
            "cardOffsetVertical" to "40px",
            "cardOffsetHorizontal" to "40px"
        ))
        layoutDialog()

        // On a centred axis a positive offset moves the card down and right, by the offset scaled
        // to this fixture's 3x density.
        assertEquals(baseY + 120f, moved.view.y, 0.01f)
        assertEquals(baseX + 120f, moved.view.x, 0.01f)
    }

    /**
     * And the sign on an axis that is anchored rather than centred: a positive offset moves the
     * card *away from* the edge it is pinned to, which is the convention the other three mobile
     * SDKs already use. Moving the card rather than padding it is also what keeps its width equal
     * to `flyoutWidth`, the width its design was just rendered to fit.
     */
    @Test
    fun `an offset moves an anchored card away from its edge`() {
        val pinnedRight = showFlyout(emptyMap(), displayPosition = "right")
        layoutDialog()
        val baseX = pinnedRight.view.x

        val movedRight = showFlyout(mapOf("cardOffsetHorizontal" to "10px"), displayPosition = "right")
        layoutDialog()
        assertEquals("a right-anchored card moves left", baseX - 30f, movedRight.view.x, 0.01f)
        assertEquals((screenWidth * 0.8).toInt(), movedRight.params.width)
        assertEquals(0, movedRight.view.paddingLeft)
        assertEquals(0, movedRight.view.paddingRight)

        val movedLeft = showFlyout(mapOf("cardOffsetHorizontal" to "10px"), displayPosition = "left")
        layoutDialog()
        assertEquals("a left-anchored card moves right", 30f, movedLeft.view.x, 0.01f)
    }

    /**
     * A translated card keeps its full width, so nothing about `cardOffsetHorizontal` changes how
     * much room the design has and the offset must not come off `maxWidthPx`. An implementation
     * that narrowed the card with margins had to subtract it; subtracting it now would render the
     * design into less room than the card actually has and leave a gap down one side.
     */

    /**
     * The two gaps the React Native pass found in its own SDK and this one shared: the control is
     * drawn OVER the content unless a band is reserved for it, and it rides on a card that a
     * horizontal offset can carry off the screen. The existing offset test covers a FLYOUT's
     * horizontal axis only, so neither of these was exercised here.
     */
    @Test
    fun `a bar reserves the close button's band so a headline cannot run under it`() {
        val bar = showBar(emptyMap())

        // The painted button is 32dp pulled out by a quarter of itself, and its margin is floored
        // at zero so the tap target's ring stays inside the card — which puts it from 8dp to 40dp
        // in from the card's right edge. Anything less than 40 here draws copy under the glyph.
        assertEquals("left gutter is unchanged", (16 * density).toInt(), bar.content.paddingLeft)
        assertTrue(
            "right gutter ${bar.content.paddingRight} must clear the control's band (40dp)",
            bar.content.paddingRight >= (40 * density).toInt()
        )
    }

    @Test
    fun `a full-width bar offset right keeps its close button on screen`() {
        val moved = showBar(mapOf("cardOffsetHorizontal" to "24px"))

        // The CARD moves by exactly the authored amount — the contract — and the CONTROL comes
        // back by the overflow, so its on-screen x is where it would have been with no offset.
        val close = (moved.view as ViewGroup).getChildAt(1)
        assertEquals("the card still moves", 24f * density, moved.view.translationX, 0.01f)
        assertEquals("the control comes back", -24f * density, close.translationX, 0.01f)

        // A LEFT-ward offset moves the right edge further inside the screen: nothing to correct.
        val left = showBar(mapOf("cardOffsetHorizontal" to "-24px"))
        assertEquals("nothing to correct", 0f, (left.view as ViewGroup).getChildAt(1).translationX, 0.01f)

        // An authored width has slack the offset is probably moving it within, so the control is
        // left alone rather than pulled off a card that never reached the edge.
        val sized = showBar(mapOf("cardWidth" to "240px", "cardOffsetHorizontal" to "24px"))
        assertEquals("a sized bar is left alone", 0f, (sized.view as ViewGroup).getChildAt(1).translationX, 0.01f)
    }

    @Test
    fun `an offset does not narrow the design inside the card`() {
        val card = showPopup(
            cssStyles = mapOf("cardOffsetHorizontal" to "40px"),
            bodyValues = mapOf("contentWidth" to "5000px")
        )
        val innerLayout = (card.content as ViewGroup).getChildAt(0)

        assertEquals(0, card.params.leftMargin)
        assertEquals(0, card.params.rightMargin)
        assertEquals(120f, card.view.translationX, 0.01f)
        // The card's own width — the 600dp default capped 16dp short of each side of this
        // 320dp fixture — and not narrowed by the offset.
        assertEquals(screenWidth - gutterPx * 2, innerLayout.layoutParams.width)
    }

    /**
     * The bar's own paddings are not where its offsets go, and stay exactly what they were before
     * these keys existed. Putting the offsets there instead would shrink the content area the
     * design was just rendered to fit, and a negative offset — which the contract allows — would
     * give the wrapper a negative padding and, with the bar's `clipChildren` off, draw the design
     * outside the card altogether.
     */
    @Test
    fun `an offset moves the bar's card and leaves its paddings alone`() {
        val card = showBar(
            cssStyles = mapOf("cardOffsetVertical" to "20px", "cardOffsetHorizontal" to "10px"),
            displayPosition = "bottom"
        )

        assertEquals("a bottom-anchored card moves up", -60f, card.view.translationY, 0.01f)
        assertEquals("a centred axis moves right", 30f, card.view.translationX, 0.01f)
        assertEquals((16 * density).toInt(), card.content.paddingLeft)
        // 42, not 16: the right gutter reserves the close button's band — see
        // `a bar reserves the close button's band`. What this row is about is that an OFFSET does
        // not change the paddings, which still holds.
        assertEquals((42 * density).toInt(), card.content.paddingRight)
        assertEquals((12 * density).toInt(), card.content.paddingTop)
        assertEquals((12 * density).toInt(), card.content.paddingBottom)
    }

    // ---- the popup card, its backdrop and the box it is placed in ------------------------------

    /**
     * The popup is a content-sized card (product decision 2026-10-08, matching sdk-swift PR #23
     * and sdk-react-native 02085f6): the design's `popupWidth` — 600 when unset — capped 16dp
     * short of the box it is placed in, as tall as its content, rounded, lifted, and centred.
     * Before 1.5.4 an unsized popup filled the window, which is what this test used to pin.
     */
    @Test
    fun `an unsized popup is a content-sized card, not the whole window`() {
        val card = showPopup(cssStyles = emptyMap())
        layoutDialog()

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, card.params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, card.params.height)
        assertEquals(Gravity.CENTER_VERTICAL, card.verticalGravity)
        assertEquals(Gravity.CENTER_HORIZONTAL, card.horizontalGravity)

        // 600dp is wider than this 320dp fixture, so the cap decides: 16dp short on each side.
        assertEquals(screenWidth - gutterPx * 2, card.view.width)
        // As tall as what it holds — the close control's band plus one line of copy — which is
        // nowhere near the window.
        val scroller = popupScrollView(card)
        assertEquals(scroller.height, card.view.height)
        assertEquals(closeBandPx + scroller.getChildAt(0).height, card.view.height)
        assertTrue("content-sized, not the window", card.view.height < screenHeight / 2)
        // Centred in the box.
        assertEquals((screenWidth - card.view.width) / 2f, card.view.x, 1f)
        assertEquals((screenHeight - card.view.height) / 2f, card.view.y, 1f)

        assertEquals(Color.WHITE, card.color)
        assertEquals(10f * density, (card.view.background as GradientDrawable).cornerRadius, 0.01f)
        assertEquals(true, card.view.clipToOutline)
        assertEquals(12f * density, card.view.elevation, 0.01f)
        assertEquals(0f, card.view.translationX, 0.01f)
        assertEquals(0f, card.view.translationY, 0.01f)
    }

    @Test
    fun `the design's popupWidth sizes an unsized card, and cardWidth wins over it`() {
        val narrow = showPopup(cssStyles = emptyMap(), bodyValues = mapOf("popupWidth" to "200px"))
        layoutDialog()
        assertEquals((200 * density).toInt(), narrow.view.width)

        val authored = showPopup(
            cssStyles = mapOf("cardWidth" to "150px"),
            bodyValues = mapOf("popupWidth" to "200px")
        )
        layoutDialog()
        assertEquals((150 * density).toInt(), authored.view.width)
    }

    /**
     * Content taller than the box: the card stops 16dp short of it at the top and the bottom and
     * the design scrolls inside, rather than the card running off the screen.
     */
    @Test
    fun `a design taller than the box is capped and scrolls inside the card`() {
        val card = showPopup(cssStyles = emptyMap(), textRows = 200)
        layoutDialog()

        assertEquals(screenHeight - gutterPx * 2, card.view.height)
        val scroller = popupScrollView(card)
        assertTrue(
            "the design must be taller than the card for this to mean anything",
            scroller.getChildAt(0).height > scroller.height
        )
    }

    /**
     * The corner radius: the authored `cardBorderRadius`, else the design's own `borderRadius`,
     * else 10 — sdk-swift's order. The bar keeps its square, flat card until a radius is authored.
     */
    @Test
    fun `a popup's corners come from cardBorderRadius, then the design's borderRadius, then 10`() {
        val fromDesign = showPopup(cssStyles = emptyMap(), bodyValues = mapOf("borderRadius" to "4px"))
        assertEquals(12f, (fromDesign.view.background as GradientDrawable).cornerRadius, 0.01f)

        val authored = showPopup(
            cssStyles = mapOf("cardBorderRadius" to "20"),
            bodyValues = mapOf("borderRadius" to "4px")
        )
        assertEquals(60f, (authored.view.background as GradientDrawable).cornerRadius, 0.01f)

        val bar = showBar(cssStyles = emptyMap())
        assertEquals(false, bar.view.clipToOutline)
    }

    /**
     * The dimmed backdrop behind the card, which the popup did not have while it filled the
     * window: black at 50% by default (the flyout's scrim and sdk-react-native's), and a tap on
     * it closes the popup as on sdk-swift and sdk-react-native. A tap on the card does not.
     */
    @Test
    fun `a tap on the dimmed backdrop closes the popup and a tap on the card does not`() {
        val card = showPopup(cssStyles = emptyMap())
        layoutDialog()
        val backdrop = dialogRoot()
        assertEquals(Color.argb(128, 0, 0, 0), (backdrop.background as ColorDrawable).color)

        // The card's own area, below the close control's band: stays up.
        tap(backdrop, card.view.x + card.view.width / 2f, card.view.y + card.view.height - 4f)
        assertEquals("a tap on the card must not close it", true, ShadowDialog.getLatestDialog().isShowing)

        // Beside the card: closes.
        tap(backdrop, screenWidth / 2f, 4f)
        assertEquals("a tap on the backdrop closes it", false, ShadowDialog.getLatestDialog().isShowing)
    }

    /**
     * A swipe that starts on the backdrop is a scroll, not a dismissal. A plain click listener
     * counted any press that stayed inside the (window-sized) backdrop as a click, so the swipe
     * that scrolled the host list on to a scroll-triggered popup closed it again at once (device
     * QA 2026-10-08, BAN-08).
     */
    @Test
    fun `a swipe across the dimmed backdrop does not close the popup`() {
        showPopup(cssStyles = emptyMap())
        layoutDialog()
        val backdrop = dialogRoot()
        val x = screenWidth / 2f
        val now = android.os.SystemClock.uptimeMillis()
        val path = listOf(
            MotionEvent.ACTION_DOWN to 4f,
            MotionEvent.ACTION_MOVE to 200f,
            MotionEvent.ACTION_MOVE to 400f,
            MotionEvent.ACTION_UP to 400f,
        )
        path.forEachIndexed { i, (action, y) ->
            val event = MotionEvent.obtain(now, now + i * 50L, action, x, y, 0)
            backdrop.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("a swipe must not close it", true, ShadowDialog.getLatestDialog().isShowing)
    }

    /**
     * A press held past the long-press timeout is still a tap here — there is no long-press action
     * on a backdrop. `GestureDetector` has long-press detection on by default, so without
     * `setIsLongpressEnabled(false)` the timer fires between DOWN and UP, `mInLongPress` is set, and
     * `ACTION_UP` never reaches `onSingleTapUp`: a slow tap stopped closing the banner.
     */
    @Test
    fun `a slow tap on the dimmed backdrop still closes the popup`() {
        showPopup(cssStyles = emptyMap())
        layoutDialog()
        val backdrop = dialogRoot()
        val x = screenWidth / 2f
        val y = 4f
        val now = android.os.SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        backdrop.dispatchTouchEvent(down)
        down.recycle()

        shadowOf(Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout() + 100L)
        )

        val upTime = now + android.view.ViewConfiguration.getLongPressTimeout() + 100L
        val up = MotionEvent.obtain(now, upTime, MotionEvent.ACTION_UP, x, y, 0)
        backdrop.dispatchTouchEvent(up)
        up.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("a slow tap must still close it", false, ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    fun `the design's overlay colour dims the backdrop`() {
        showPopup(cssStyles = emptyMap(), bodyValues = mapOf("popupOverlay_backgroundColor" to "#ff000080"))

        assertEquals(Color.argb(0x80, 0xff, 0, 0), (dialogRoot().background as ColorDrawable).color)
    }

    // Per-type window insets (`WindowInsetsCompat.Builder.setInsets`) only round-trip faithfully
    // through a real platform `android.view.WindowInsets` from API 30 on, which is what backs
    // these tests' `dispatchInsets` calls; below that, androidx's compat shim collapses everything
    // back to one legacy `systemWindowInsets` value and the per-axis assertions would not be
    // exercising what they claim to.

    /**
     * The box the card is placed in is the window less the system bars and cutout the window
     * REPORTS: the backdrop pads itself to them, the gravity places the card inside that, and the
     * cap is taken from it — measured, not assumed from the display. The insets are deliberately
     * asymmetric, because every symmetric pair hides a centring error.
     */
    @Test
    @Config(sdk = [30])
    fun `a popup card is placed inside the system bars the window reports`() {
        // Anchored on both axes: flush with the bars' inner edges.
        val anchored = showPopup(cssStyles = mapOf(
            "cardWidth" to "200px", "cardHeight" to "300px",
            "cardPositionVertical" to "bottom", "cardPositionHorizontal" to "right"
        ))
        dispatchInsets(dialogRoot(), top = 100, left = 20, right = 30, bottom = 40)
        layoutDialog()
        assertEquals(listOf(20, 100, 30, 40), dialogRoot().let {
            listOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
        })
        assertEquals((screenHeight - 40 - anchored.view.height).toFloat(), anchored.view.y, 0.5f)
        assertEquals((screenWidth - 30 - anchored.view.width).toFloat(), anchored.view.x, 0.5f)

        // Unsized and centred: in the centre of what can be seen, not of the display.
        val centred = showPopup(cssStyles = emptyMap())
        dispatchInsets(dialogRoot(), top = 100, left = 20, right = 30, bottom = 40)
        layoutDialog()
        assertEquals(
            (100 + (screenHeight - 40)) / 2f,
            centred.view.y + centred.view.height / 2f, 1.5f
        )
        assertEquals(
            (20 + (screenWidth - 30)) / 2f,
            centred.view.x + centred.view.width / 2f, 1.5f
        )
        // And the cap is taken from that box: 16dp short of its sides, not the display's.
        assertEquals(screenWidth - 20 - 30 - gutterPx * 2, centred.view.width)

        // Bars that go away unwind to zero: the padding reads the insets exactly, unfloored, so a
        // window with a hidden status bar does not hold a top-anchored card below its edge.
        dispatchInsets(dialogRoot(), top = 0, left = 0, right = 0, bottom = 0)
        assertEquals(0, dialogRoot().paddingTop)
        assertEquals(0, dialogRoot().paddingBottom)
    }

    /** An authored size is capped the same way a content size is, against the same box. */
    @Test
    @Config(sdk = [30])
    fun `an authored size larger than the box is capped 16dp short of it`() {
        val card = showPopup(cssStyles = mapOf("cardWidth" to "5000px", "cardHeight" to "5000px"))
        dispatchInsets(dialogRoot(), top = 100, left = 20, right = 30, bottom = 40)
        layoutDialog()

        assertEquals(screenWidth - 20 - 30 - gutterPx * 2, card.view.width)
        assertEquals(screenHeight - 100 - 40 - gutterPx * 2, card.view.height)
    }

    /**
     * The card is inside the bars, so its content needs no inset of its own: the only padding on
     * the scroller is the band the close control sits in. And the control still lands on the
     * card's own corner, clamped into the safe area.
     */
    @Config(sdk = [30])
    @Test
    fun `a popup's content clears only the close control, and the control sits on the card's corner`() {
        for (cssStyles in listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("cardWidth" to "50%"),
            mapOf("cardWidth" to "50%", "cardHeight" to "50%")
        )) {
            val card = showPopup(cssStyles = cssStyles)
            val scrollView = popupScrollView(card)
            val closeButton = popupCloseButton()
            dispatchInsets(dialogRoot(), top = 100, left = 20, right = 30, bottom = 40)
            layoutDialog()

            assertEquals("$cssStyles", closeBandPx, scrollView.paddingTop)
            assertEquals("$cssStyles", 0, scrollView.paddingLeft)
            assertEquals("$cssStyles", 0, scrollView.paddingRight)
            assertEquals("$cssStyles", 0, scrollView.paddingBottom)

            // Every term is the PAINTED square's; the view is `closeRingPx` wider on each side and
            // is placed that much back from it, so the control a person sees has not moved.
            val maxX = (screenWidth - 30 - closeMarginPx - closeSidePx).toFloat()
            val minY = (maxOf(100, statusBarHeight) + closeMarginPx).toFloat()
            val expectedX = (card.view.x + card.view.width - closeSidePx - closeMarginPx).coerceAtMost(maxX)
            val expectedY = (card.view.y + closeMarginPx).coerceAtLeast(minY)
            assertEquals("$cssStyles", expectedX - closeRingPx, closeButton.x, 0.01f)
            assertEquals("$cssStyles", expectedY - closeRingPx, closeButton.y, 0.01f)
            assertEquals("$cssStyles", closeTapTargetPx, closeButton.layoutParams.width)
        }
    }

    /**
     * The control tracks `popupContainer`'s own laid-out rect — its top-right corner, inset by the
     * control's margin — via the `OnLayoutChangeListener` registered on it, so a card smaller than
     * the window carries its control with it rather than leaving it in the window's corner.
     */
    @Test
    fun `a popup's close button sits at a sized and centred card's own corner, not the window's`() {
        val card = showPopup(cssStyles = mapOf("cardWidth" to "50%", "cardHeight" to "50%"))
        layoutDialog()
        val closeButton = popupCloseButton()

        // The card is well clear of every screen edge at 50%/50% centred, so the button's own
        // safe-area clamp cannot be what is putting it here.
        assertEquals(
            card.view.x + card.view.width - closeSidePx - closeMarginPx - closeRingPx,
            closeButton.x, 0.01f
        )
        assertEquals(card.view.y + closeMarginPx - closeRingPx, closeButton.y, 0.01f)
        assertTrue(
            "must not be left at the window's corner",
            closeButton.x < screenWidth - closeSidePx - closeMarginPx - closeRingPx - 1f
        )
    }

    /**
     * A card narrower than the control's own box: a button inside it would hang outside its
     * bounds, and `ViewGroup.dispatchTouchEvent` only forwards a pointer to a child the pointer
     * falls inside. Staying a sibling of the card keeps it reachable at any card size.
     */
    @Test
    fun `a popup's close button is a sibling of the card, not a child, and stays clickable`() {
        val card = showPopup(cssStyles = mapOf("cardWidth" to "10px", "cardHeight" to "10px"))
        layoutDialog()
        val closeButton = popupCloseButton()

        assertEquals("the card must be smaller than the button for this to mean anything", 30, card.view.width)
        assertEquals(-1, (card.view as ViewGroup).indexOfChild(closeButton))

        closeButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, ShadowDialog.getLatestDialog().isShowing)
    }

    /**
     * The flyout's content-padding rule, which had no assertion at all until this test: the gate
     * could have been deleted and the suite would have stayed green. (The popup shared it until
     * the content-sized card put every popup inside the bars, where its content needs no inset.)
     *
     * The first attempt here used `screenHeight - statusBarHeight` — no bottom inset, no
     * reference to the anchor — and a flyout's vertical gravity is author-settable, so a card
     * around 92% tall passed it on the `bottom` and `center` arms and was then laid out with its
     * own top edge under the status bar. `100%` was caught, which is what made that shape look
     * complete, so the tall-but-not-full case is the one asserted here.
     *
     * 92% is chosen to DISCRIMINATE, not for roundness: at this harness's geometry the old shape
     * drops the padding at or below 1310px and the correct one at or below 1270px, so only a card
     * between those two separates them. 93% sits above both and would have passed either way —
     * which it did, until the differential run showed the test was green for the wrong reason.
     */
    @Test
    @Config(sdk = [30])
    fun `a tall flyout that still covers a bar keeps its content padding`() {
        // Fits inside the visible box: the card is clear, and the clearance comes off the content.
        val fits = showFlyout(mapOf("cardHeight" to "40%"), displayPosition = "right")
        layoutDialog()
        dispatchInsets(fits.view, top = 100, left = 0, right = 0, bottom = 40)
        assertEquals("a flyout inside the visible box drops it", 0, flyoutContent(fits).paddingTop)

        // Tall enough to cover a bar on an anchored arm, but NOT 100% — the case the first shape
        // let through. The padding has to stay.
        for (anchor in listOf("bottom", "center")) {
            val tall = showFlyout(
                mapOf("cardHeight" to "92%", "cardPositionVertical" to anchor),
                displayPosition = "right"
            )
            layoutDialog()
            dispatchInsets(tall.view, top = 100, left = 0, right = 0, bottom = 40)
            assertEquals(
                "a 92% flyout anchored $anchor still covers a bar",
                maxOf(100, statusBarHeight), flyoutContent(tall).paddingTop
            )
        }
    }

    /**
     * THE FLOOR: every dispatch above uses `top = 100`, above the `statusBarHeight` floor, so
     * `maxOf(bars.top, statusBarHeight)` and `bars.top` read the same number throughout and the
     * flyout's clearance gate (and the `else -> fTop` padding arm below it) could be reverted to
     * the unfloored `bars.top` with the suite still green.
     *
     * The flyout's default vertical gravity is `TOP`, so a sized card's top IS `bars.top` by
     * construction (`fTopV` at the `TOP` arm), with no need to dial a height until the card's
     * distance from the edge becomes a function of it. `top = 0` alone separates the two
     * readings: unfloored, the gate reads `0 >= 0` (clear, padding drops to 0); floored, it
     * reads `0 >= statusBarHeight` (not clear, padding stays).
     */
    @Test
    @Config(sdk = [30])
    fun `a sized flyout on a zero-reporting window still measures clearance against the floor`() {
        val sized = showFlyout(mapOf("cardHeight" to "40%"), displayPosition = "right")
        layoutDialog()
        dispatchInsets(sized.view, top = 0, left = 0, right = 0, bottom = 0)
        assertEquals(
            "a window reporting zero is not a window with no status bar",
            statusBarHeight, flyoutContent(sized).paddingTop
        )
    }

    // ---- the close control, which reads five `cssStyles` keys of its own -----------------------

    /**
     * `BannerCloseButtonStyle` resolves those keys and `BannerCloseButtonStyleTest` pins what it
     * resolves them to; what the three tests below add is that each display type actually draws
     * what it resolved, and that nothing else gets a say.
     *
     * The control's colours used to come from the Unlayer design first —
     * `popupCloseButton_iconColor` and `popupCloseButton_backgroundColor` — and only then from
     * `cssStyles`. The web SDK never read those, so an editor default written into a design
     * overrode what the author had actually set. The lookup is gone. (`QA-CLS-08`.)
     */
    @Test
    fun `a popup's close button no longer takes its colours from the Unlayer design`() {
        showPopup(
            cssStyles = emptyMap(),
            bodyValues = mapOf(
                "popupCloseButton_backgroundColor" to "#00ff00",
                "popupCloseButton_iconColor" to "#ff00ff"
            )
        )
        val close = popupCloseButton()

        assertEquals(Color.WHITE, closeFill(close))
        assertEquals(Color.BLACK, closeGlyphColor(close))
    }

    /** `QA-CLS-09` and `QA-CLS-10`: the keys reach the other two types, not only the popup. */
    @Test
    fun `a bar and a flyout paint their close button from the same keys`() {
        val bar = showBar(mapOf("closeButtonColor" to "#fff", "closeButtonBackgroundColor" to "#000"))
        val barClose = (bar.view as ViewGroup).getChildAt(1)
        assertEquals(Color.BLACK, closeFill(barClose))
        assertEquals(Color.WHITE, closeGlyphColor(barClose))

        val flyout = showFlyout(
            mapOf("closeButtonColor" to "#0a0", "closeButtonBackgroundColor" to "#fff"),
            displayPosition = "right"
        )
        val flyoutClose = ((flyout.view as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)
        assertEquals(Color.WHITE, closeFill(flyoutClose))
        assertEquals(Color.parseColor("#00aa00"), closeGlyphColor(flyoutClose))
    }

    @Test
    fun `a translucent glyph colour keeps its alpha`() {
        showPopup(cssStyles = mapOf("closeButtonColor" to "#00000080"))

        assertEquals(Color.argb(0x80, 0, 0, 0), closeGlyphColor(popupCloseButton()))
    }

    /**
     * Device QA (CLS-01) found the admin default `#000` drawn as #666 grey: the system
     * `ic_menu_close_clear_cancel` icon is itself ~60% opaque, and a SRC_IN tint keeps the icon's
     * alpha. The glyph is now the SDK's own drawable, painted in exactly the authored colour.
     */
    @Test
    fun `the default glyph is fully opaque black, not a tinted system icon`() {
        showPopup(cssStyles = emptyMap())
        val close = popupCloseButton() as ImageButton

        assertTrue(close.drawable is CloseGlyphDrawable)
        assertEquals(null, close.colorFilter)
        assertEquals(Color.BLACK, closeGlyphColor(close))
    }

    /**
     * `CloseGlyphDrawable` draws corner to corner of the bounds `ImageView.configureBounds` gives
     * it (no intrinsic size, no scale type consulted), so the painted box IS
     * `glyphSizeDp * density` — pins the sizing chain end to end, at the default `closeFontSize`
     * and at `24` (`QA-CLS-05`).
     */
    @Test
    fun `the glyph's painted box is glyphSizeDp square, at the default size and at 24`() {
        showPopup(cssStyles = emptyMap())
        var close = popupCloseButton() as ImageButton
        var expected = (14 * density).roundToInt()
        assertEquals(expected, close.drawable.bounds.width())
        assertEquals(expected, close.drawable.bounds.height())

        showPopup(cssStyles = mapOf("closeFontSize" to "24"))
        close = popupCloseButton() as ImageButton
        expected = (24 * density).roundToInt()
        assertEquals(expected, close.drawable.bounds.width())
        assertEquals(expected, close.drawable.bounds.height())
    }

    /**
     * The bar's 48dp tap target is a TouchDelegate on the card, and it is shifted into the card's
     * bounds rather than clipped to them: the square sits 4dp from the top, so a ring grown
     * around it would otherwise lose its top 4dp.
     */
    @Test
    fun `a tap in the bar's ring below the painted square closes the bar`() {
        val bar = showBar(emptyMap())
        val card = bar.view as ViewGroup
        assertTrue("the card must hold a 48dp target", card.height >= 48 * density)

        val x = screenWidth - 24 * density
        val y = 46 * density
        tap(card, x, y)

        assertEquals("a tap 46dp below the card's top is inside the 48dp target", null, card.parent)
    }

    @Test
    fun `a tap well below the bar's ring does not close it`() {
        val bar = showBar(emptyMap())
        val card = bar.view as ViewGroup
        assertTrue("the card must hold a 48dp target", card.height >= 48 * density)

        tap(card, screenWidth - 60 * density, 20 * density)

        assertTrue(card.parent != null)
    }

    /**
     * Device QA: a tap on a bar anywhere but a link or its close target went THROUGH it to the
     * app underneath (on the QA app it opened "Favorite Products"). The bar is an overlay drawn
     * over the screen, and a view that does not consume a touch hands it to whatever is beneath.
     * Dispatched to the activity's content root, as the window would, so the app's own view is a
     * real candidate for the touch rather than one the test can never reach.
     */
    @Test
    fun `a tap on a bar's body does not fall through to the app underneath`() {
        var appClicks = 0
        val bar = showBar(emptyMap())
        val card = bar.view as ViewGroup
        val screen = card.rootView.findViewById<ViewGroup>(android.R.id.content)
        // The host's own content, which `wrapChildren` moved into the holder under the bar.
        val outer = screen.getChildAt(0) as ViewGroup
        val holder = (outer.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        holder.getChildAt(0).setOnClickListener { appClicks++ }
        assertTrue("the fixture bar must be laid out", card.height > 0)

        // 8dp in from the bar's left edge, in the content wrapper's 16dp padding: on the bar and
        // on nothing inside it that could consume the touch itself.
        tap(screen, card.left + 8 * density, card.top + card.height / 2f)
        assertEquals("the app under the bar must not be clicked", 0, appClicks)
        assertTrue("and the bar stays up", card.parent != null)

        // The control's 48dp target still works through the same dispatch.
        tap(screen, card.left + screenWidth - 24 * density, card.top + 46 * density)
        assertEquals("the close target dismisses the bar", null, card.parent)
        assertEquals("without clicking the app", 0, appClicks)

        // And the app is reachable once the bar is gone: the listener is wired, so the zero above
        // is the bar consuming the touch and not a tap that reached nothing.
        tap(screen, screen.width / 2f, screen.height / 2f)
        assertEquals(1, appClicks)
    }

    @Test
    fun `cardBackgroundColor transparent leaves the popup card see-through`() {
        val card = showPopup(cssStyles = mapOf("cardBackgroundColor" to "transparent"))

        assertEquals(Color.TRANSPARENT, card.color)
    }

    /**
     * The ✕ must be ABOVE the card in Z, not only after it in child order: Android dispatches a
     * touch by Z first, so a close control below the card's elevation is covered by the clickable
     * card and never receives the tap (device QA 2026-10-08, CLS-01…08 on the first card build).
     * Dispatched through the dialog's root, as a finger's would be — not via performClick.
     */
    @Test
    fun `a tap on the popup's close control through the dialog root closes the popup`() {
        showPopup(cssStyles = emptyMap())
        val root = dialogRoot()
        val close = popupCloseButton()
        val card = close.parent as ViewGroup
        assertTrue(
            "the close control must sit above the card's elevation",
            close.z > (0 until card.childCount).map { card.getChildAt(it) }
                .filter { it !== close }.maxOf { it.z }
        )
        val rootAt = IntArray(2).also { root.getLocationInWindow(it) }
        val closeAt = IntArray(2).also { close.getLocationInWindow(it) }
        tap(
            root,
            (closeAt[0] - rootAt[0]) + close.width / 2f,
            (closeAt[1] - rootAt[1]) + close.height / 2f
        )
        assertEquals(false, ShadowDialog.getLatestDialog().isShowing)
    }

    private fun tap(target: View, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** `QA-CLS-10`'s border, read off the drawn view rather than off the resolver. */
    @Test
    fun `a flyout draws the border shorthand as a stroke of that width and colour`() {
        val flyout = showFlyout(
            mapOf(
                "closeButtonColor" to "#0a0",
                "closeButtonBackgroundColor" to "#fff",
                "closeButtonBorder" to "3px solid #0a0"
            ),
            displayPosition = "right"
        )
        val close = ((flyout.view as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)
        assertEquals((3 * density).roundToInt(), closeStrokeWidth(close))
        assertEquals(Color.parseColor("#00aa00"), closeStrokeColor(close))
    }

    @Test
    fun `a default popup draws no ring and a circle`() {
        showPopup(emptyMap())
        val close = popupCloseButton()
        assertEquals(0, closeStrokeWidth(close))
        assertEquals(16f * density, closeCornerRadius(close), 0.01f)
    }

    @Test
    fun `a zero border radius squares the drawn button`() {
        showPopup(mapOf("closeButtonBorderRadius" to "0"))
        assertEquals(0f, closeCornerRadius(popupCloseButton()), 0.01f)
    }

    /**
     * `topMargin = closeMargin` keeps the painted square's top and side insets equal at every
     * size, at the cost of the row (and the card below it) growing by `8dp - ring` once the ring
     * shrinks below 8dp — 0 at the default size, 5dp at closeFontSize 24.
     */
    @Test
    fun `a flyout's close row grows with the top margin at a larger closeFontSize`() {
        showFlyout(cssStyles = mapOf("closeFontSize" to "24"), displayPosition = "right")
        layoutDialog()

        val side = (42 * density).toInt()
        val tapTarget = (48 * density).toInt()
        val ring = (tapTarget - side) / 2
        val margin = ((8 * density).toInt() - ring).coerceAtLeast(0)
        assertEquals(margin + tapTarget, dialogCard().getChildAt(0).height)
    }

    /** The painted square's top and side insets must agree, not just at the default size. */
    @Test
    fun `a flyout's close button sits the same distance from the top and side edges at closeFontSize 24`() {
        showFlyout(cssStyles = mapOf("closeFontSize" to "24"), displayPosition = "right")
        layoutDialog()

        val closeRow = dialogCard().getChildAt(0) as ViewGroup
        val lp = closeRow.getChildAt(0).layoutParams as FrameLayout.LayoutParams
        val sideMargin = if (lp.rightMargin != 0) lp.rightMargin else lp.leftMargin
        assertEquals(lp.topMargin, sideMargin)
    }

    /** The bar's view is the painted square, so a compact bar's height is not floored by a 48dp view. */
    @Test
    fun `a bar's close view is the painted square and its inner edge stays inside the gutter`() {
        val bar = showBar(mapOf("closeFontSize" to "24"))
        val close = (bar.view as ViewGroup).getChildAt(1)
        val side = (42 * density).toInt()
        val lp = close.layoutParams as FrameLayout.LayoutParams
        assertEquals(side, lp.width)
        assertEquals(side, lp.height)
        assertEquals(0, lp.marginEnd)
    }

    /**
     * The row above the flyout's scrolled design is sized by the control's margins and the view
     * inside it, and that view is now a 48dp tap target rather than the 32dp square it paints. The
     * margins absorb the difference, so the row — and everything below it — stays where it was.
     */
    @Test
    fun `a flyout's close row stays as tall as the painted control and its margins`() {
        showFlyout(cssStyles = emptyMap(), displayPosition = "right")
        layoutDialog()

        assertEquals(closeMarginPx * 2 + closeSidePx, dialogCard().getChildAt(0).height)
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

    /**
     * The half of the above the existing gravity assertion doesn't reach: `statusBarPad` and the
     * close button's `topMargin` both key off `verticalGravity == Gravity.TOP`, and on master that
     * was driven only by `displayPosition`. An authored `cardPositionVertical` can now take
     * `verticalGravity` off `TOP` with `displayPosition` absent entirely, which this pins by
     * asserting the padding it drops rather than only the gravity that drops it.
     */
    @Test
    fun `cardPositionVertical bottom drops a bar's status-bar padding without displayPosition`() {
        val card = showBar(mapOf("cardPositionVertical" to "bottom"))

        assertEquals((12 * density).toInt(), card.content.paddingTop)
    }

    @Test
    fun `cardPositionHorizontal overrides the displayPosition a flyout falls back to`() {
        val card = showFlyout(mapOf("cardPositionHorizontal" to "left"), displayPosition = "right")

        assertEquals(Gravity.START, card.horizontalGravity)
    }

    @Test
    fun `an offset may be negative and a size may not`() {
        val offset = showPopup(cssStyles = mapOf("cardOffsetVertical" to "-10px"))
        assertEquals(-30f, offset.view.translationY, 0.01f)

        val size = showBar(cssStyles = mapOf("cardWidth" to "-50px"))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, size.params.width)
    }

    /**
     * However it is spelled. A zero-width card is an invisible one that has already counted as an
     * impression, and `0.1px` reaches it by a different route: a positive length that still rounds
     * to a zero layout dimension. An offset has no matching case — a zero displacement is exactly
     * what an absent offset already does — so the vocabulary's bare `0` needs no special handling
     * on either side.
     */
    @Test
    fun `a size may not be zero`() {
        for (value in listOf("0", "0px", "0%", "0.1px")) {
            assertEquals(
                "cardWidth=$value should have changed nothing",
                ViewGroup.LayoutParams.MATCH_PARENT,
                showBar(cssStyles = mapOf("cardWidth" to value)).params.width
            )
        }
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

        /**
         * The card's paint: a flat colour, or a rounded card's fill (the popup is always rounded).
         * Null where the view carries neither, which is what a bar's card carried.
         */
        val color get() = when (val bg = view.background) {
            is ColorDrawable -> bg.color
            is GradientDrawable -> bg.color?.defaultColor
            else -> null
        }

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
            view.translationX, view.translationY,
            color, view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom,
            content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom,
            (content.layoutParams as FrameLayout.LayoutParams).gravity
        )
    }

    /**
     * The control paints a square inside a transparent ring that widens its view to a tap target,
     * so its fill is the inset drawable's own, not the view's background directly.
     */
    private fun closeFill(button: View): Int? =
        ((button.background as InsetDrawable).drawable as GradientDrawable).color?.defaultColor

    private fun closeDrawable(button: View): GradientDrawable =
        (button.background as InsetDrawable).drawable as GradientDrawable

    private fun closeCornerRadius(button: View): Float = closeDrawable(button).cornerRadius

    private fun strokePaint(button: View): Paint? {
        val field = try {
            GradientDrawable::class.java.getDeclaredField("mStrokePaint")
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "GradientDrawable.mStrokePaint is gone or renamed; update strokePaint() to match " +
                    "the framework's current internals",
                e
            )
        }
        return field.apply { isAccessible = true }.get(closeDrawable(button)) as Paint?
    }

    private fun closeStrokeWidth(button: View): Int = strokePaint(button)?.strokeWidth?.roundToInt() ?: 0

    private fun closeStrokeColor(button: View): Int = strokePaint(button)!!.color

    private fun closeGlyphColor(button: View): Int =
        ((button as ImageButton).drawable as CloseGlyphDrawable).color

    /** The bar's card, which the manager adds as an overlay beside the wrapper it built. */
    private fun showBar(cssStyles: Map<String, Any?>, displayPosition: String? = null): Card {
        val activity = show(banner("bar", cssStyles, displayPosition))
        val outer = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        assertEquals("expected the content wrapper plus one bar overlay", 2, outer.childCount)
        val bar = outer.getChildAt(1) as ViewGroup
        return Card(bar, bar.getChildAt(0))
    }

    /**
     * The popup's card: the first child of the dialog's root layout — which is the dimmed backdrop
     * — holding a scrolled design.
     */
    private fun showPopup(
        cssStyles: Map<String, Any?>,
        bodyValues: Map<String, Any?> = emptyMap(),
        displayPosition: String? = null,
        textRows: Int = 1
    ): Card {
        show(banner("popup", cssStyles, displayPosition, bodyValues = bodyValues, textRows = textRows))
        val popup = dialogCard()
        return Card(popup, (popup.getChildAt(0) as ScrollView).getChildAt(0))
    }

    private fun popupScrollView(card: Card): ScrollView = (card.view as ViewGroup).getChildAt(0) as ScrollView

    /** The flyout's card: the dialog's overlay holds it, and its second child is the scroller. */
    /** The `flyoutContainer` whose top padding is the flyout's status-bar clearance. */
    private fun flyoutContent(card: Card): View = card.view

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

        /**
         * The design's top in the scroller's own content space, wrapper offset included, measured
         * from the top of its viewport — below the popup's close band, which is the scroller's
         * own top padding.
         */
        val designTop: Int
            get() = design.top + (if (aligned) view.getChildAt(0).top else 0) - view.paddingTop

        /** The height the design asks for when nothing constrains it — what a ScrollView gives it. */
        fun measureContentHeight(): Int {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return design.measuredHeight
        }

        /** Lays the scroller out with a viewport [height] tall, its own padding added on top. */
        fun layoutAt(height: Int) {
            val total = height + view.paddingTop + view.paddingBottom
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(total, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, total)
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
     * Drives a real measure and layout pass over the latest dialog's root, at screen size, so that
     * where a card actually lands can be read from `View.getX()`/`getY()` rather than inferred
     * from its `LayoutParams`. For the offsets that distinction is the whole point: a symmetric
     * margin and a translation both show up on a `LayoutParams`, and only one of them moves a
     * centred card.
     */
    private fun layoutDialog() {
        val root = dialogRoot()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, screenWidth, screenHeight)
    }

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
        bodyValues: Map<String, Any?> = emptyMap(),
        textRows: Int = 1
    ) = BannerResponse(
        token = "chrome-$displayType-${tokenCounter++}",
        displayType = displayType,
        cssSelector = TARGET_SELECTOR,
        displayPosition = displayPosition,
        cssStyles = cssStyles,
        design = designWithText(bodyValues, textRows)
    )

    private fun designWithText(bodyValues: Map<String, Any?>, rows: Int = 1): Map<String, Any?> = mapOf(
        "body" to mapOf(
            "rows" to List(rows) {
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
            },
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
