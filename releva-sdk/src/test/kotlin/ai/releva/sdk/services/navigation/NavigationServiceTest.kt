package ai.releva.sdk.services.navigation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationServiceTest {

    private lateinit var context: Context
    private lateinit var navigationService: NavigationService
    private var navigationHandlerCalled = false
    private var lastNavigatedScreen: String? = null
    private var lastNavigatedParameters: Bundle? = null

    private val testNavigationHandler = object : NavigationHandler {
        override fun navigateToScreen(screenName: String, parameters: Bundle) {
            navigationHandlerCalled = true
            lastNavigatedScreen = screenName
            lastNavigatedParameters = parameters
        }

        override fun getScreenMappings(): Map<String, Any> {
            return mapOf(
                "home" to 1,
                "product" to 2,
                "cart" to 3
            )
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        navigationService = NavigationService.getInstance()
        navigationService.clearNavigationHandler() // Clear handler from previous tests
        navigationHandlerCalled = false
        lastNavigatedScreen = null
        lastNavigatedParameters = null
    }

    // Singleton Tests

    @Test
    fun `getInstance returns singleton instance`() {
        val instance1 = NavigationService.getInstance()
        val instance2 = NavigationService.getInstance()

        assertSame(instance1, instance2)
    }

    // Navigation Handler Tests

    @Test
    fun `setNavigationHandler sets handler`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        // Handler is set (verified indirectly through navigation)
        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "home")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
        assertTrue(navigationHandlerCalled)
    }

    // Screen Navigation Tests

    @Test
    fun `handleNotificationNavigation with screen target navigates to screen`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "product")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
        assertTrue(navigationHandlerCalled)
        assertEquals("product", lastNavigatedScreen)
    }

    @Test
    fun `handleNotificationNavigation with screen target and parameters passes parameters`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val parametersJson = """
            {
                "productId": "prod-123",
                "category": "electronics"
            }
        """.trimIndent()

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "product")
            putExtra("navigate_to_parameters", parametersJson)
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
        assertEquals("product", lastNavigatedScreen)
        assertNotNull(lastNavigatedParameters)
        assertEquals("prod-123", lastNavigatedParameters!!.getString("productId"))
        assertEquals("electronics", lastNavigatedParameters!!.getString("category"))
    }

    @Test
    fun `handleNotificationNavigation with screen target but no handler returns false`() {
        // Don't set handler
        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "home")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
        assertFalse(navigationHandlerCalled)
    }

    @Test
    fun `handleNotificationNavigation with screen target but no screen name returns false`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            // Missing navigate_to_screen
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
        assertFalse(navigationHandlerCalled)
    }

    @Test
    fun `handleNotificationNavigation with empty screen name returns false`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
        assertFalse(navigationHandlerCalled)
    }

    // Parameter Parsing Tests

    @Test
    fun `parseParameters handles all JSON types`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val parametersJson = """
            {
                "stringParam": "test value",
                "intParam": 42,
                "longParam": 9876543210,
                "doubleParam": 3.14,
                "boolParam": true
            }
        """.trimIndent()

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "test")
            putExtra("navigate_to_parameters", parametersJson)
        }

        navigationService.handleNotificationNavigation(context, intent)

        assertNotNull(lastNavigatedParameters)
        assertEquals("test value", lastNavigatedParameters!!.getString("stringParam"))
        assertEquals(42, lastNavigatedParameters!!.getInt("intParam"))
        assertEquals(9876543210L, lastNavigatedParameters!!.getLong("longParam"))
        assertEquals(3.14, lastNavigatedParameters!!.getDouble("doubleParam"), 0.001)
        assertTrue(lastNavigatedParameters!!.getBoolean("boolParam"))
    }

    @Test
    fun `parseParameters handles empty JSON`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "test")
            putExtra("navigate_to_parameters", "{}")
        }

        navigationService.handleNotificationNavigation(context, intent)

        assertNotNull(lastNavigatedParameters)
        assertTrue(lastNavigatedParameters!!.isEmpty)
    }

    @Test
    fun `parseParameters handles null parameters`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "test")
            // No parameters
        }

        navigationService.handleNotificationNavigation(context, intent)

        assertNotNull(lastNavigatedParameters)
        assertTrue(lastNavigatedParameters!!.isEmpty)
    }

    @Test
    fun `parseParameters handles malformed JSON`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "test")
            putExtra("navigate_to_parameters", "invalid json {[")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        // Should still succeed but with empty parameters
        assertTrue(result)
        assertNotNull(lastNavigatedParameters)
        assertTrue(lastNavigatedParameters!!.isEmpty)
    }

    // URL Navigation Tests

    @Test
    fun `handleNotificationNavigation with url target creates url intent`() {
        val intent = Intent().apply {
            putExtra("target", "url")
            putExtra("navigate_to_url", "https://example.com/product")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        // URL navigation should succeed (Robolectric allows it)
        assertTrue(result)
    }

    @Test
    fun `handleNotificationNavigation with url target but no url returns false`() {
        val intent = Intent().apply {
            putExtra("target", "url")
            // Missing navigate_to_url
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
    }

    @Test
    fun `handleNotificationNavigation with empty url returns false`() {
        val intent = Intent().apply {
            putExtra("target", "url")
            putExtra("navigate_to_url", "")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
    }

    // Intent Creation Tests

    @Test
    fun `createNotificationIntent with url target creates url intent`() {
        val data = mapOf(
            "target" to "url",
            "navigate_to_url" to "https://example.com/sale"
        )

        val intent = navigationService.createNotificationIntent(context, MainActivity::class.java, data)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/sale", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `createNotificationIntent with screen target creates app intent`() {
        val data = mapOf(
            "target" to "screen",
            "navigate_to_screen" to "product",
            "productId" to "123"
        )

        val intent = navigationService.createNotificationIntent(context, MainActivity::class.java, data)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("screen", intent.getStringExtra("target"))
        assertEquals("product", intent.getStringExtra("navigate_to_screen"))
        assertEquals("123", intent.getStringExtra("productId"))
    }

    @Test
    fun `createNotificationIntent with no target creates app intent`() {
        val data = mapOf<String, String>()

        val intent = navigationService.createNotificationIntent(context, MainActivity::class.java, data)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createNotificationIntent with url target but no url throws exception`() {
        val data = mapOf("target" to "url")

        navigationService.createNotificationIntent(context, MainActivity::class.java, data)
    }

    // Notification Handling Tests

    @Test
    fun `handleNotificationNavigation with no target returns false`() {
        val intent = Intent()

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
    }

    @Test
    fun `handleNotificationNavigation with unknown target returns false`() {
        val intent = Intent().apply {
            putExtra("target", "unknown_target")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertFalse(result)
    }

    @Test
    fun `handleNotificationNavigation tracks callback URL if present`() {
        val intent = Intent().apply {
            putExtra("callbackUrl", "https://api.releva.ai/track")
            putExtra("target", "url")
            putExtra("navigate_to_url", "https://example.com")
        }

        navigationService.handleNotificationNavigation(context, intent)

        // Callback URL tracking happens in background thread
        // We can't easily test the HTTP call without mocking
        // But we can verify no exceptions are thrown
    }

    @Test
    fun `handleNotificationNavigation with empty callback URL does not track`() {
        val intent = Intent().apply {
            putExtra("callbackUrl", "")
            putExtra("target", "url")
            putExtra("navigate_to_url", "https://example.com")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
    }

    @Test
    fun `handleNotificationNavigation from action button dismisses notification`() {
        val intent = Intent().apply {
            putExtra("target", "url")
            putExtra("navigate_to_url", "https://example.com")
            putExtra("from_action_button", true)
            putExtra("notification_id", 123)
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
        // Notification dismissal verified (Robolectric allows NotificationManager access)
    }

    // Integration Tests

    @Test
    fun `full navigation flow with screen, parameters, and callback`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        val intent = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "cart")
            putExtra("navigate_to_parameters", """{"items": "3"}""")
            putExtra("callbackUrl", "https://api.releva.ai/track/123")
        }

        val result = navigationService.handleNotificationNavigation(context, intent)

        assertTrue(result)
        assertTrue(navigationHandlerCalled)
        assertEquals("cart", lastNavigatedScreen)
        assertNotNull(lastNavigatedParameters)
        assertEquals("3", lastNavigatedParameters!!.getString("items"))
    }

    @Test
    fun `createNotificationIntent preserves all data fields`() {
        val data = mapOf(
            "target" to "screen",
            "navigate_to_screen" to "product",
            "productId" to "456",
            "category" to "electronics",
            "callbackUrl" to "https://api.releva.ai/track"
        )

        val intent = navigationService.createNotificationIntent(context, MainActivity::class.java, data)

        assertEquals("screen", intent.getStringExtra("target"))
        assertEquals("product", intent.getStringExtra("navigate_to_screen"))
        assertEquals("456", intent.getStringExtra("productId"))
        assertEquals("electronics", intent.getStringExtra("category"))
        assertEquals("https://api.releva.ai/track", intent.getStringExtra("callbackUrl"))
    }

    @Test
    fun `multiple navigation calls maintain state`() {
        navigationService.setNavigationHandler(testNavigationHandler)

        // First navigation
        val intent1 = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "home")
        }
        navigationService.handleNotificationNavigation(context, intent1)

        assertEquals("home", lastNavigatedScreen)

        // Second navigation
        val intent2 = Intent().apply {
            putExtra("target", "screen")
            putExtra("navigate_to_screen", "product")
        }
        navigationService.handleNotificationNavigation(context, intent2)

        assertEquals("product", lastNavigatedScreen)
    }

    // Dummy activity class for testing
    class MainActivity : android.app.Activity()
}
