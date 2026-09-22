package ai.releva.sdk.ui.banner

import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.services.banner.BannerDisplayController
import ai.releva.sdk.services.banner.BannerSessionStore
import ai.releva.sdk.types.response.BannerResponse
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
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
        // BannerSessionStore is a process-scoped singleton shared by every test in this JVM.
        // Retention is not: it lives in each host's own ViewModelStore and goes away with the
        // host in tearDown, so there is nothing global to reset here.
        BannerSessionStore.startNewSession()

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
     * The half that guards BAN-13, for an Activity: navigating away and back in the same session
     * must not put the banner up again. Two independent things stop it — the destroy is not a
     * configuration change, so nothing is retained, and the screen the user arrives at is a
     * different host instance with a `ViewModelStore` of its own, so there would be nothing there
     * for it to find either way. The test below isolates the first of those.
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
     * The other half of BAN-13, and the case that makes the `isChangingConfigurations` gate
     * load-bearing rather than belt-and-braces: a Fragment whose *view* is destroyed by a
     * navigation while the Fragment itself stays on the back stack. The instance survives, so
     * its `ViewModelStore` survives with it, and the hand-off slot is still there when the user
     * comes back. Only the gate keeps it empty, because that teardown is not a configuration
     * change.
     *
     * Differential: drop the `isChangingConfigurations` check in `detach()` and this fails with
     * the banner back on screen, which is BAN-13 verbatim ("shown once and not re-shown on
     * navigating away and back in the same session").
     */
    @Test
    fun `a fragment view destroyed by a navigation does not restore the banner when the user returns`() {
        val activity = buildHost().setup().get()
        activity.setContentView(FrameLayout(activity).apply { id = CONTAINER_ID })

        val fragment = BannerHostFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(CONTAINER_ID, fragment, "banner-host")
            .commitNow()
        BannerDisplayManager(client, TARGET_SELECTOR) {}.attach(fragment)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(activity, staticBanner))

        // Navigate away: detach() destroys the fragment's view — and with it the banner — but
        // keeps the fragment instance, exactly as a replace()/addToBackStack() would.
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        assertNull("the destroyed view still holds the banner", findBanner(activity, staticBanner))

        // Navigate back: same fragment instance, same ViewModelStore, new view and new manager.
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        BannerDisplayManager(client, TARGET_SELECTOR) {}.attach(fragment)

        assertNull(findBanner(activity, staticBanner))
    }

    /**
     * The other reachability bound: a banner is handed back only to the screen it was retained
     * for. A different screen area of the same host (a different `targetSelector`, which is a
     * different `ViewModelProvider` key) does not get it, and neither does a different host
     * instance, whose `ViewModelStore` is its own.
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

        // And a later host is a different instance with a store of its own, so it cannot pick
        // the retained banner up either.
        val late = buildHost().setup().get()
        attachManager(late)
        assertNull(findBanner(late, staticBanner))
    }

    /**
     * One configuration change destroys every live host, so every attached manager detaches and
     * hands over what it has — including the managers with nothing on screen — and then every
     * recreation attaches, in an order nothing guarantees. A screen that hands over nothing must
     * not discard another screen's hand-off, whichever of them attaches first. Otherwise the fix
     * stops applying as soon as an app has a second manager, which the per-screen
     * `targetSelector` in `README.md` is exactly the shape of.
     *
     * The empty-handed screen's recreation attaches **first** here, which is the order that
     * matters: with any store the hosts share, its attach is what would drain the other's
     * hand-off before that one ever got to ask for it.
     */
    @Test
    fun `a screen with no banner does not discard another screen's retained one`() {
        val controllerA = buildHost()
        val hostA = controllerA.setup().get()
        attachManager(hostA)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(hostA, staticBanner))

        // A second screen is live with its own selector and nothing on screen.
        val controllerB = buildHost()
        val hostB = controllerB.setup().get()
        attachManager(hostB, targetSelector = "#other")

        // One configuration change destroys both, before either recreation attaches. B is
        // rotated to the opposite orientation because Robolectric's Configuration is
        // process-wide, so rotating to landscape again after A already did would be a no-op
        // diff and B would never be recreated at all.
        val recreatedA = rotate(controllerA, hostA)
        val recreatedB = rotate(controllerB, hostB, Configuration.ORIENTATION_PORTRAIT)

        attachManager(recreatedB, targetSelector = "#other")
        attachManager(recreatedA)
        assertNotNull(findBanner(recreatedA, staticBanner))
    }

    /**
     * Two *live* instances of the same Activity class — a standard-launch-mode Activity opened
     * twice, with the same `targetSelector` and a banner each — then the device rotates and both
     * are relaunched. Nothing about the class, the selector, or the order the recreations attach
     * in tells the two apart; what does is that each instance has a `ViewModelStore` of its own,
     * which the platform hands to that instance's own replacement. So each gets its own banner
     * back and neither gets the other's.
     *
     * B's recreation attaches first, which is the order that would misattribute under any scheme
     * keyed on something the two instances share.
     */
    @Test
    fun `two live instances of the same host class each restore their own banner`() {
        val controllerA = buildHost()
        val hostA = controllerA.setup().get()
        attachManager(hostA)
        val bannerA = staticBanner.copy(token = "rotate-static-a")
        display(bannerA)
        assertNotNull("fixture never displayed banner A", findBanner(hostA, bannerA))
        // A backgrounds (as it would when the user opens B) so its collector is not still
        // picking up B's banner below — bannerFlow is process-wide, not per-host.
        controllerA.pause()
        shadowOf(Looper.getMainLooper()).idle()

        val controllerB = buildHost()
        val hostB = controllerB.setup().get()
        attachManager(hostB)
        val bannerB = staticBanner.copy(token = "rotate-static-b")
        display(bannerB)
        assertNotNull("fixture never displayed banner B", findBanner(hostB, bannerB))

        // Both instances are live in the foreground stack when the configuration change hits, so
        // both detach with a banner up, and both detaches run before either recreation attaches.
        // B is rotated to the opposite orientation — Robolectric's Configuration is process-wide,
        // so rotating it to landscape again after A already did would be a no-op diff and B would
        // never be recreated; the direction doesn't matter to what this test exercises, only that
        // both hosts go through a genuine isChangingConfigurations recreate.
        controllerA.resume()
        shadowOf(Looper.getMainLooper()).idle()
        val recreatedA = rotate(controllerA, hostA)
        val recreatedB = rotate(controllerB, hostB, Configuration.ORIENTATION_PORTRAIT)

        attachManager(recreatedB)
        assertNotNull(findBanner(recreatedB, bannerB))
        assertNull(findBanner(recreatedB, bannerA))

        attachManager(recreatedA)
        assertNotNull(findBanner(recreatedA, bannerA))
        assertNull(findBanner(recreatedA, bannerB))
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
        original: BannerHostActivity,
        toOrientation: Int = Configuration.ORIENTATION_LANDSCAPE
    ): BannerHostActivity {
        controller.configurationChange(
            Configuration(original.resources.configuration).apply {
                orientation = toOrientation
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
        // Fixed rather than View.generateViewId(), so the id survives the fragment being
        // detached and re-attached to the container in the navigation test.
        const val CONTAINER_ID = 0x7e000001
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

/**
 * A fragment for the navigation test to attach a manager to. Its view is a ViewGroup with a
 * child, which is what `BannerDisplayManager.wrapChildren` expects of a screen it wraps.
 */
class BannerHostFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FrameLayout(requireContext()).apply { addView(View(requireContext())) }
}
