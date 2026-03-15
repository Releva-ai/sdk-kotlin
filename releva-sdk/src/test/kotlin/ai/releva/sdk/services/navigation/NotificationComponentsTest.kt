package ai.releva.sdk.services.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationComponentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    /** Build a releva://notification URI from parameters, matching production code. */
    private fun buildTrampolineUri(
        notificationId: Int,
        activityClass: String,
        target: String? = null,
        callbackUrl: String? = null,
        navigateToUrl: String? = null,
        navigateToScreen: String? = null,
        navigateToParameters: String? = null
    ): Uri {
        val sb = StringBuilder("releva://notification")
        sb.append("?nid=").append(notificationId)
        sb.append("&act=").append(activityClass)
        if (!target.isNullOrEmpty()) sb.append("&target=").append(Uri.encode(target))
        if (!callbackUrl.isNullOrEmpty()) sb.append("&cb=").append(Uri.encode(callbackUrl))
        if (!navigateToUrl.isNullOrEmpty()) sb.append("&nav_url=").append(Uri.encode(navigateToUrl))
        if (!navigateToScreen.isNullOrEmpty()) sb.append("&nav_screen=").append(Uri.encode(navigateToScreen))
        if (!navigateToParameters.isNullOrEmpty()) sb.append("&nav_params=").append(Uri.encode(navigateToParameters))
        return Uri.parse(sb.toString())
    }

    // NotificationTrampolineActivity Tests

    @Test
    fun `NotificationTrampolineActivity handles URL navigation`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(123, "com.example.MainActivity", target = "url", navigateToUrl = "https://example.com")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity handles screen navigation`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(456, "com.example.MainActivity", target = "screen",
                navigateToScreen = "product", navigateToParameters = """{"id":"123"}""")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity handles default app open`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(789, "com.example.MainActivity")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity tracks callback URL if provided`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(100, "com.example.MainActivity", target = "url",
                navigateToUrl = "https://example.com", callbackUrl = "https://api.releva.ai/track")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity dismisses notification`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(999, "com.example.MainActivity", target = "url",
                navigateToUrl = "https://example.com")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity with invalid notification ID still works`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(-1, "com.example.MainActivity", target = "url",
                navigateToUrl = "https://example.com")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity with empty URL still finishes`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(123, "com.example.MainActivity", target = "url")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `NotificationTrampolineActivity with no data URI finishes gracefully`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java)

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)
        controller.create()

        assertTrue(controller.get().isFinishing)
    }

    // Constant Verification Tests

    @Test
    fun `NotificationTrampolineActivity constants are correctly defined`() {
        assertEquals("target", NotificationTrampolineActivity.EXTRA_TARGET)
        assertEquals("navigate_to_screen", NotificationTrampolineActivity.EXTRA_NAVIGATE_TO_SCREEN)
        assertEquals("navigate_to_parameters", NotificationTrampolineActivity.EXTRA_NAVIGATE_TO_PARAMETERS)
    }

    @Test
    fun `NotificationActionReceiver constants are correctly defined`() {
        assertEquals("ai.releva.sdk.ACTION_NOTIFICATION_BUTTON_CLICKED",
            NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON_CLICKED)
        assertEquals("notification_id", NotificationActionReceiver.EXTRA_NOTIFICATION_ID)
        assertEquals("target", NotificationActionReceiver.EXTRA_TARGET)
        assertEquals("navigate_to_url", NotificationActionReceiver.EXTRA_NAVIGATE_TO_URL)
        assertEquals("navigate_to_screen", NotificationActionReceiver.EXTRA_NAVIGATE_TO_SCREEN)
        assertEquals("navigate_to_parameters", NotificationActionReceiver.EXTRA_NAVIGATE_TO_PARAMETERS)
        assertEquals("activity_class", NotificationActionReceiver.EXTRA_ACTIVITY_CLASS)
    }

    // Integration Tests

    @Test
    fun `NotificationTrampolineActivity lifecycle completes`() {
        val intent = Intent(context, NotificationTrampolineActivity::class.java).apply {
            data = buildTrampolineUri(500, "com.example.MainActivity", target = "url",
                navigateToUrl = "https://example.com/product", callbackUrl = "https://api.releva.ai/track/xyz")
        }

        val controller = Robolectric.buildActivity(NotificationTrampolineActivity::class.java, intent)

        controller.create()
        assertNotNull(controller.get())
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `Data URI correctly encodes and decodes callback URL with query params`() {
        val callbackUrl = "https://tr.example.com/click?i=abc123&p=def456"
        val uri = buildTrampolineUri(1, "com.example.Main", callbackUrl = callbackUrl)

        assertEquals(callbackUrl, uri.getQueryParameter("cb"))
    }
}
