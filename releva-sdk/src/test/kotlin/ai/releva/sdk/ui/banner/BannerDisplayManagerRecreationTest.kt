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
 * A banner that was on screen when the device was rotated has to still be on screen afterwards.
 *
 * Everything `BannerDisplayManager` puts up belongs to the host instance it attached to: popups
 * and flyouts are `Dialog`s on that activity's window, bars and static banners are views built at
 * runtime inside its tree. A configuration change destroys the instance and raises `ON_DESTROY`
 * exactly like a real teardown, so `detach` took all of it down and the recreated instance
 * attached to an empty screen — and since 1.5.1 marks a token shown the moment it renders,
 * nothing emitted it again for the rest of the session.
 *
 * The other half is what must *not* happen. 1.5.1's once-per-session guarantee (CHANGELOG
 * "BAN-13": shown once, not re-shown on navigating away and back) means the restore path has to
 * be reachable only by a host being recreated. The three negative tests below are that bound;
 * they hold with the hand-off removed too, and exist to stay holding with it in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BannerDisplayManagerRecreationTest {

    private val requestPaths = CopyOnWriteArrayList<String>()
    private val hosts = mutableListOf<ActivityController<BannerHostActivity>>()
    private lateinit var client: RelevaClient
    private var trackingServer: MockWebServer? = null

    @Before
    fun setUp() {
        // BannerSessionStore is a process-scoped singleton shared by every test in this JVM.
        // The retention is not: it lives in each host's own ViewModelStore and goes away with
        // the host in tearDown, so there is nothing global to reset here.
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
     * attached would still be collecting during the next test in this JVM and would put a second
     * copy of its banner on screen.
     */
    @After
    fun tearDown() {
        hosts.forEach { it.close() }
        trackingServer?.shutdown()
    }

    /**
     * A static banner is rendered by `DesignRenderer` and inserted into the wrapper, not inflated
     * from the host's layout, so the recreated host's own view tree does not bring it back on its
     * own — with the hand-off removed this fails, the banner absent.
     */
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

    /** The same for a `Dialog`-based type, whose window went down with the old activity's. */
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
     * The impression was tracked when the banner first rendered, and what comes back from a
     * rotation is that same impression — not a second one, however often the user turns the
     * phone. Counted at the wire, since `trackImpression` fires from the host's own scope rather
     * than through a test seam.
     */
    @Test
    fun `a restore does not track a second impression`() {
        val controller = buildHost()
        val original = controller.setup().get()
        attachManager(original)
        display(staticBanner)
        assertEquals("initial impression never arrived: $requestPaths", 1, awaitImpressions(1))

        val recreated = rotate(controller, original)
        attachManager(recreated)
        assertNotNull("nothing was restored, so this proves nothing", findBanner(recreated, staticBanner))

        // A duplicate would have been dispatched by the attach above, which has already run;
        // this only gives it time to reach the server.
        awaitImpressions(2, timeoutMs = 1_000)
        assertEquals("tracked: $requestPaths", 1, impressions())
    }

    /**
     * BAN-13 for an Activity host: the screen the user arrives at after a genuine destroy is a
     * different instance with a `ViewModelStore` of its own, and the store of the one that was
     * finished was cleared rather than handed over.
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
     * Two *live* instances of the same Activity class — a standard-launch-mode activity opened
     * twice, same `targetSelector`, a banner each — rotated together. Nothing about the class, the
     * selector or the order the recreations attach in tells the two apart; what does is that each
     * instance has a `ViewModelStore` of its own, which the platform hands to that instance's own
     * replacement. B's recreation attaches first, which is the order that would misattribute
     * under any scheme keyed on something the two instances share.
     */
    @Test
    fun `two live instances of the same host class each restore their own banner`() {
        val controllerA = buildHost()
        val hostA = controllerA.setup().get()
        attachManager(hostA)
        val bannerA = staticBanner.copy(token = "rotate-static-a")
        display(bannerA)
        assertNotNull("fixture never displayed banner A", findBanner(hostA, bannerA))

        // A backgrounds, as it would when the user opens B, so its collector is not still picking
        // up B's banner below — bannerFlow is process-wide, not per host.
        controllerA.pause()
        shadowOf(Looper.getMainLooper()).idle()

        val controllerB = buildHost()
        val hostB = controllerB.setup().get()
        attachManager(hostB)
        val bannerB = staticBanner.copy(token = "rotate-static-b")
        display(bannerB)
        assertNotNull("fixture never displayed banner B", findBanner(hostB, bannerB))

        // Both are live in the foreground stack when the configuration change hits, so both hand
        // over before either recreation attaches. B is rotated to the opposite orientation because
        // Robolectric's Configuration is process-wide: rotating it to landscape again after A
        // already did would be a no-op diff and B would never be recreated at all.
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

    /**
     * A Fragment host reaches its `ViewModelStore` by a different platform mechanism from an
     * Activity's — `FragmentManagerViewModel` keeps one per fragment `mWho` and hands it to the
     * instance restored under the same `mWho` — so it is worth pinning rather than assuming.
     * Fails with the hand-off removed, the banner absent.
     */
    @Test
    fun `a fragment host restores its banner after a configuration change`() {
        val controller = buildHost()
        val original = controller.setup().get()
        val fragment = addFragment(original)
        attachManager(fragment)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(original, staticBanner))

        val recreated = rotate(controller, original)
        val restoredFragment = hostFragment(recreated)
        assertNotSame("the fragment was never recreated", fragment, restoredFragment)
        attachManager(restoredFragment)

        assertNotNull(findBanner(recreated, staticBanner))
    }

    /**
     * BAN-13 for a Fragment host, and the case an Activity host cannot express: a navigation
     * destroys the fragment's *view* while the fragment itself — and therefore its
     * `ViewModelStore` — stays on the back stack. The hand-off is written from the host activity's
     * `ON_DESTROY`, which this teardown never reaches, so the slot the returning fragment finds is
     * empty. Fails if the hand-off is moved to the fragment's own view lifecycle, which is what
     * makes this the test that pins the choice of lifecycle rather than a restatement of BAN-13.
     */
    @Test
    fun `a fragment view destroyed by a navigation does not restore the banner when the user returns`() {
        val activity = buildHost().setup().get()
        val fragment = addFragment(activity)
        attachManager(fragment)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(activity, staticBanner))

        navigateAway(activity, fragment)
        assertNull("the destroyed view still holds the banner", findBanner(activity, staticBanner))

        navigateBack(activity, fragment)
        attachManager(fragment)

        assertNull(findBanner(activity, staticBanner))
    }

    /**
     * The same bound with a configuration change folded into it: the fragment is on the back stack
     * with its view — and its banner — already gone when the device is rotated, so the recreation
     * it goes through must not resurrect a banner that was not on screen. `detach` takes the
     * hand-off observer off the activity's lifecycle when the navigation destroys the view, so the
     * rotation has nothing to hand over. Fails too if the hand-off moves to the view lifecycle.
     */
    @Test
    fun `a configuration change while the fragment is on the back stack does not restore its banner`() {
        val controller = buildHost()
        val original = controller.setup().get()
        val fragment = addFragment(original)
        attachManager(fragment)
        display(staticBanner)
        assertNotNull("fixture never displayed the banner", findBanner(original, staticBanner))

        navigateAway(original, fragment)
        val recreated = rotate(controller, original)

        val restoredFragment = hostFragment(recreated)
        navigateBack(recreated, restoredFragment)
        attachManager(restoredFragment)

        assertNull(findBanner(recreated, staticBanner))
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

    private fun attachManager(
        fragment: Fragment,
        targetSelector: String = TARGET_SELECTOR
    ): BannerDisplayManager =
        BannerDisplayManager(client, targetSelector) {}.also { it.attach(fragment) }

    private fun addFragment(activity: AppCompatActivity): BannerHostFragment =
        BannerHostFragment().also {
            activity.supportFragmentManager.beginTransaction()
                .add(CONTAINER_ID, it, FRAGMENT_TAG)
                .commitNow()
        }

    private fun hostFragment(activity: AppCompatActivity): BannerHostFragment =
        activity.supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as BannerHostFragment

    /** Destroys the fragment's view and keeps the instance, exactly as a back-stacked
     * `replace()` does. */
    private fun navigateAway(activity: AppCompatActivity, fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
    }

    /** The user comes back: same fragment instance, same `ViewModelStore`, new view. */
    private fun navigateBack(activity: AppCompatActivity, fragment: Fragment) {
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
    }

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
        assertNotSame("the host was never recreated", original, recreated)
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
        const val FRAGMENT_TAG = "banner-host"
    }
}

// Fixed rather than View.generateViewId(), so the container the fragment is restored into has the
// same id on the recreated activity.
private const val CONTAINER_ID = 0x7e000001

/**
 * The host the tests above rotate. Themed in code because this module's manifest declares no
 * activity for it, so Robolectric gives it the platform default theme and `AppCompatActivity`
 * refuses to build its sub-decor ("You need to use a Theme.AppCompat theme") — the same reason
 * `StoryViewerActivity`'s manifest entry pins a theme explicitly. Its content view is the
 * fragment container, set in `onCreate` so the recreated instance has it before its
 * FragmentManager restores the fragment into it.
 */
class BannerHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
    }
}

/**
 * A fragment for the fragment-host tests to attach a manager to. Its view is a ViewGroup with a
 * child, which is what `BannerDisplayManager.wrapChildren` expects of a screen it wraps.
 */
class BannerHostFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FrameLayout(requireContext()).apply { addView(View(requireContext())) }
}
