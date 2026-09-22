package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerRetentionStore
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
 * A banner that was on screen when the device was rotated has to still be on screen
 * afterwards.
 *
 * Everything `BannerDisplayManager` puts up is bound to the host instance: popups and flyouts
 * are `Dialog`s on the activity's window, bars and static banners are views in its tree. A
 * configuration change destroys that instance and `detach` tears all of it down, so before
 * 1.5.2 the banner vanished — and, since 1.5.1 marks a token shown once it renders, nothing
 * would emit it again for the rest of the session.
 *
 * The other half is what must *not* happen: the restore path has to be reachable only by a
 * recreation. Reaching it from a navigation would re-show a banner already marked shown, which
 * is the once-per-session guarantee 1.5.1 exists to provide (CHANGELOG "BAN-13").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BannerDisplayManagerConfigChangeTest {

    private val requestPaths = CopyOnWriteArrayList<String>()
    private val hosts = mutableListOf<ActivityController<BannerHostActivity>>()
    private lateinit var client: RelevaClient
    private var trackingServer: MockWebServer? = null

    @Before
    fun setUp() {
        // Both stores are process-scoped singletons shared by every test in this JVM.
        BannerSessionStore.startNewSession()
        BannerRetentionStore.clear()

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestPaths.add(request.path ?: "")
                return MockResponse().setResponseCode(200)
            }
        }
        server.start()
        trackingServer = server
        client = RelevaClient(RuntimeEnvironment.getApplication(), "", "test-token").apply {
            setEndpointOverride(server.url("/").toString().trimEnd('/'))
        }
    }

    /**
     * Every host is destroyed, which is what detaches the manager attached to it.
     * `BannerDisplayController.bannerFlow` is a process-wide `SharedFlow`, so a manager left
     * attached would still be collecting during the next test in this JVM and would put a
     * second copy of its banner on screen.
     */
    @After
    fun tearDown() {
        hosts.forEach { it.close() }
        trackingServer?.shutdown()
    }

    @Test
    fun `a static banner is still on screen after a configuration change`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(original, staticBanner))

        val recreated = rotate(controller, original)
        attachManager(recreated)

        assertNotNull(findBanner(recreated, staticBanner))
    }

    @Test
    fun `a popup banner is still on screen after a configuration change`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(popupBanner)
        assertEquals("fixture never displayed the banner", 1, showingDialogs())

        val recreated = rotate(controller, original)
        attachManager(recreated)

        // The original's dialog went down with its window; exactly one is up on the new one.
        assertEquals(1, showingDialogs())
    }

    /**
     * The impression is tracked when a banner is displayed, and the banner that comes back
     * from a rotation is the same impression of the same banner — one rotation must not report
     * a second one. Counted at the wire, since `trackImpression` fires from the host's own
     * scope rather than through a test seam.
     */
    @Test
    fun `a configuration change does not track a second impression`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(staticBanner)
        assertEquals("initial impression never arrived: $requestPaths", 1, awaitImpressions(1))

        val recreated = rotate(controller, original)
        attachManager(recreated)

        // A duplicate would be dispatched by the attach above, which has already run; this only
        // gives it time to reach the server.
        awaitImpressions(2, timeoutMs = 1_000)
        assertEquals("tracked: $requestPaths", 1, impressions())
    }

    /**
     * The half that guards BAN-13. A destroy that is not a configuration change retains
     * nothing, so the next screen to attach — here the same screen, revisited, which is exactly
     * the navigate-away-and-back case BAN-13 is about — starts empty. Without the
     * `isChangingConfigurations` gate this restores the banner and BAN-13 regresses.
     */
    @Test
    fun `a genuine destroy does not put the banner back on the next screen`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(staticBanner)
        controller.pause().stop().destroy()

        val next = buildHost().setup().get()
        attachManager(next)

        assertNull(findBanner(next, staticBanner))
    }

    /**
     * The other reachability bound: the banner is handed back to the screen it was retained
     * for, and a screen that is not that one both fails to get it and empties the slot, so it
     * cannot be picked up later either.
     */
    @Test
    fun `a banner retained for one screen is not restored onto a different one`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(staticBanner)

        val recreated = rotate(controller, original)
        // A different screen of the same app attaches first: a different selector is what
        // makes it a different screen, and static banners are selector-matched anyway.
        attachManager(recreated, targetSelector = "#other")
        assertNull(findBanner(recreated, staticBanner))

        // And the slot is empty now, so the screen it was retained for does not get it late.
        val late = buildHost().setup().get()
        attachManager(late)
        assertNull(findBanner(late, staticBanner))
    }

    private val staticBanner = BannerResponse(
        token = "rotate-static",
        displayType = "static",
        cssSelector = TARGET_SELECTOR,
        design = designWithText("Rotate me")
    )

    private val popupBanner = BannerResponse(
        token = "rotate-popup",
        displayType = "popup",
        design = designWithText("Rotate me")
    )

    private fun buildHost(): ActivityController<BannerHostActivity> =
        Robolectric.buildActivity(BannerHostActivity::class.java).also { hosts.add(it) }

    private fun attachManager(
        activity: AppCompatActivity,
        targetSelector: String = TARGET_SELECTOR
    ): BannerDisplayManager =
        BannerDisplayManager(client, targetSelector) {}.also { it.attach(activity) }

    /** Emits [banner] and drains the collector that renders it. */
    private fun display(banner: BannerResponse) {
        BannerDisplayController.showBanner(banner)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Rotates [original]'s own configuration and returns the instance that comes back. A bare
     * activity declares no `android:configChanges`, so Robolectric recreates it exactly as the
     * platform does; the `assertNotSame` is what makes every assertion that follows mean
     * something, since an instance that was never replaced would still hold its own banner.
     */
    private fun rotate(
        controller: ActivityController<BannerHostActivity>,
        original: BannerHostActivity
    ): BannerHostActivity {
        controller.configurationChange(
            Configuration(original.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
        )
        val recreated = controller.get()
        assertNotSame(original, recreated)
        return recreated
    }

    /** The static banner's rendered view, which [BannerDisplayManager] tags with its token. */
    private fun findBanner(activity: AppCompatActivity, banner: BannerResponse): View? =
        activity.window.decorView.findViewWithTag<View>("releva_banner_${banner.token}")

    private fun showingDialogs(): Int = ShadowDialog.getShownDialogs().count { it.isShowing }

    private fun impressions(): Int = requestPaths.count { it == IMPRESSIONS_PATH }

    /** Polls until [expected] impressions have arrived or the timeout runs out. */
    private fun awaitImpressions(expected: Int, timeoutMs: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (impressions() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return impressions()
    }

    private fun designWithText(text: String): Map<String, Any?> = mapOf(
        "body" to mapOf(
            "rows" to listOf(
                mapOf(
                    "columns" to listOf(
                        mapOf(
                            "contents" to listOf(
                                mapOf(
                                    "type" to "text",
                                    "values" to mapOf("text" to "<p>$text</p>")
                                )
                            )
                        )
                    ),
                    "values" to emptyMap<String, Any?>()
                )
            ),
            "values" to emptyMap<String, Any?>()
        )
    )

    private companion object {
        const val TARGET_SELECTOR = "#home-content"
        const val IMPRESSIONS_PATH = "/api/v0/impressions"
    }
}

/**
 * The host the tests above rotate. Themed in code because this module's manifest declares no
 * activity for it, so Robolectric gives it the platform default theme and `AppCompatActivity`
 * refuses to build its sub-decor ("You need to use a Theme.AppCompat theme") — the same reason
 * `StoryViewerActivity`'s manifest entry pins a theme explicitly.
 */
class BannerHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        super.onCreate(savedInstanceState)
    }
}
