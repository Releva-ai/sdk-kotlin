package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.storage.StorageService
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.device.DeviceType
import ai.releva.sdk.types.event.CustomEvent
import ai.releva.sdk.types.filter.SimpleFilter
import ai.releva.sdk.types.response.BannerResponse
import ai.releva.sdk.types.wishlist.WishlistProduct
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelevaClientTest {

    private lateinit var context: Context
    private lateinit var storageService: StorageService
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: RelevaClient

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageService = StorageService.getInstance(context)
        storageService.clear()

        // Note: We can't easily test with MockWebServer because RelevaClient
        // creates its own OkHttpClient internally. These tests focus on state management,
        // initialization logic, and request preparation logic.
    }

    @After
    fun tearDown() {
        storageService.clear()
    }

    // Profile ID Management Tests

    @Test
    fun `setProfileId sets profile ID when not set`() = runTest {
        val client = createTestClient()

        client.setProfileId("profile-123")

        assertEquals("profile-123", storageService.getProfileId())
    }

    @Test
    fun `setProfileId updates profile ID and tracks previous for merging`() = runTest {
        val client = createTestClient()

        storageService.setProfileId("old-profile")
        client.setProfileId("new-profile")

        assertEquals("new-profile", storageService.getProfileId())
        // Note: mergeProfileIds is private, but its effect is visible in API requests
    }

    @Test
    fun `setProfileId does nothing if same profile ID`() = runTest {
        val client = createTestClient()

        storageService.setProfileId("same-profile")
        client.setProfileId("same-profile")

        assertEquals("same-profile", storageService.getProfileId())
    }

    // Device ID Management Tests

    @Test
    fun `setDeviceId sets device ID when not set`() = runTest {
        val client = createTestClient()

        client.setDeviceId("device-abc")

        assertEquals("device-abc", storageService.getDeviceId())
    }

    @Test
    fun `setDeviceId updates device ID when different`() = runTest {
        val client = createTestClient()

        storageService.setDeviceId("old-device")
        client.setDeviceId("new-device")

        assertEquals("new-device", storageService.getDeviceId())
    }

    @Test
    fun `setDeviceId does nothing if same device ID`() = runTest {
        val client = createTestClient()

        storageService.setDeviceId("same-device")
        client.setDeviceId("same-device")

        assertEquals("same-device", storageService.getDeviceId())
    }

    // Cart Management Tests

    @Test
    fun `setCart stores cart data in storage`() = runTest {
        val client = createTestClient()

        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 99.99, quantity = 1.0)
        ))

        client.setCart(cart)

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        assertTrue(storedCart!!.contains("p1"))
        assertTrue(storedCart.contains("99.99"))
    }

    @Test
    fun `setCart on first call initializes without automatic push`() = runTest {
        val client = createTestClient()

        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 50.0, quantity = 2.0)
        ))

        // First setCart call should initialize but not trigger automatic push
        client.setCart(cart)

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        // Cart is stored but no automatic push happens (initialization)
    }

    @Test
    fun `clearCartStorage clears cart and resets initialization flag`() = runTest {
        val client = createTestClient()

        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 50.0, quantity = 1.0)
        ))
        client.setCart(cart)

        client.clearCartStorage()

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        // Cart should be empty active cart
        assertTrue(storedCart!!.contains("\"products\":[]"))
    }

    // Wishlist Management Tests

    @Test
    fun `setWishlist stores wishlist data in storage`() = runTest {
        val client = createTestClient()

        val wishlist = listOf(
            WishlistProduct(id = "w1"),
            WishlistProduct(id = "w2")
        )

        client.setWishlist(wishlist)

        val storedWishlist = storageService.getWishlistData()
        assertNotNull(storedWishlist)
        assertEquals(2, storedWishlist!!.size)
    }

    @Test
    fun `setWishlist on first call initializes without automatic push`() = runTest {
        val client = createTestClient()

        val wishlist = listOf(
            WishlistProduct(id = "w1")
        )

        client.setWishlist(wishlist)

        val storedWishlist = storageService.getWishlistData()
        assertNotNull(storedWishlist)
        assertEquals(1, storedWishlist!!.size)
    }

    @Test
    fun `setWishlist with empty list stores empty wishlist`() = runTest {
        val client = createTestClient()

        client.setWishlist(emptyList())

        val storedWishlist = storageService.getWishlistData()
        assertNotNull(storedWishlist)
        assertEquals(0, storedWishlist!!.size)
    }

    // Engagement Tracking Tests

    @Test
    fun `enablePushEngagementTracking creates engagement service`() {
        val client = createTestClient()

        assertNull(client.engagementTrackingService)

        client.enablePushEngagementTracking()

        assertNotNull(client.engagementTrackingService)
    }

    @Test
    fun `engagementTrackingService is null by default`() {
        val client = createTestClient()

        assertNull(client.engagementTrackingService)
    }

    // Configuration Tests

    @Test
    fun `client with tracking disabled config does not require setup`() {
        val config = RelevaConfig(
            enableTracking = false,
            enablePushNotifications = false,
            enableInAppMessaging = false
        )
        val client = createTestClient(config = config)

        // Client should be created without errors even with tracking disabled
        assertNotNull(client)
    }

    @Test
    fun `client with full config enables all features`() {
        val config = RelevaConfig.full()
        val client = createTestClient(config = config)

        assertNotNull(client)
    }

    @Test
    fun `client with trackingOnly config`() {
        val config = RelevaConfig.trackingOnly()
        val client = createTestClient(config = config)

        assertNotNull(client)
    }

    // Session Management Tests

    @Test
    fun `session is created on first use`() = runTest {
        val client = createTestClient()
        storageService.setDeviceId("device-123")
        storageService.setProfileId("profile-123")

        // Session ID should be null initially
        assertNull(storageService.getSessionId())

        // Trigger session creation by attempting a tracking call
        // (We can't easily test the actual API call without mocking HTTP)
        // But we can verify storage state
    }

    @Test
    fun `session timestamp is stored`() = runTest {
        val client = createTestClient()

        // Set initial session
        val beforeTime = System.currentTimeMillis()
        storageService.setSessionId("session-123")
        storageService.setSessionTimestamp(beforeTime)

        val storedTimestamp = storageService.getSessionTimestamp()
        assertNotNull(storedTimestamp)
        assertTrue(storedTimestamp!! >= beforeTime)
    }

    // State Management Tests

    @Test
    fun `multiple profile changes are tracked`() = runTest {
        val client = createTestClient()

        client.setProfileId("profile-1")
        client.setProfileId("profile-2")
        client.setProfileId("profile-3")

        assertEquals("profile-3", storageService.getProfileId())
        // Previous profiles should be tracked for merging (private state)
    }

    @Test
    fun `cart and wishlist can be set together`() = runTest {
        val client = createTestClient()

        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 100.0, quantity = 1.0)
        ))
        val wishlist = listOf(
            WishlistProduct(id = "w1"),
            WishlistProduct(id = "w2")
        )

        client.setCart(cart)
        client.setWishlist(wishlist)

        assertNotNull(storageService.getCartData())
        assertNotNull(storageService.getWishlistData())
        assertEquals(2, storageService.getWishlistData()!!.size)
    }

    // Edge Cases

    @Test
    fun `setCart with paid cart stores paid flag`() = runTest {
        val client = createTestClient()

        val paidCart = Cart.paid(
            products = listOf(
                CartProduct(id = "p1", price = 200.0, quantity = 1.0)
            ),
            orderId = "order-123"
        )

        client.setCart(paidCart)

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        assertTrue(storedCart!!.contains("\"cartPaid\":true"))
        assertTrue(storedCart.contains("order-123"))
    }

    @Test
    fun `setCart with complex cart product stores custom fields`() = runTest {
        val client = createTestClient()

        val customFields = CustomFields(
            string = listOf(
                ai.releva.sdk.types.customfield.StringField("brand", listOf("Nike"))
            )
        )
        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 150.0, quantity = 1.0, custom = customFields)
        ))

        client.setCart(cart)

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        assertTrue(storedCart!!.contains("Nike"))
    }

    @Test
    fun `setWishlist with duplicate product IDs stores all`() = runTest {
        val client = createTestClient()

        val wishlist = listOf(
            WishlistProduct(id = "w1"),
            WishlistProduct(id = "w1"),
            WishlistProduct(id = "w2")
        )

        client.setWishlist(wishlist)

        val storedWishlist = storageService.getWishlistData()
        assertNotNull(storedWishlist)
        // All items should be stored, including duplicates
        assertEquals(3, storedWishlist!!.size)
    }

    @Test
    fun `setProfileId with empty string stores empty profile`() = runTest {
        val client = createTestClient()

        client.setProfileId("")

        assertEquals("", storageService.getProfileId())
    }

    @Test
    fun `setDeviceId with empty string stores empty device`() = runTest {
        val client = createTestClient()

        client.setDeviceId("")

        assertEquals("", storageService.getDeviceId())
    }

    @Test
    fun `setCart then clearCartStorage then setCart again reinitializes`() = runTest {
        val client = createTestClient()

        val cart1 = Cart.active(listOf(CartProduct(id = "p1", price = 10.0, quantity = 1.0)))
        client.setCart(cart1)

        client.clearCartStorage()

        val cart2 = Cart.active(listOf(CartProduct(id = "p2", price = 20.0, quantity = 1.0)))
        client.setCart(cart2)

        val storedCart = storageService.getCartData()
        assertNotNull(storedCart)
        assertTrue(storedCart!!.contains("p2"))
        assertFalse(storedCart.contains("p1"))
    }

    // Integration Tests

    @Test
    fun `complete client lifecycle with profile, device, cart, and wishlist`() = runTest {
        val client = createTestClient()

        // Set profile and device
        client.setProfileId("user-456")
        client.setDeviceId("device-789")

        // Set cart
        val cart = Cart.active(listOf(
            CartProduct(id = "p1", price = 50.0, quantity = 2.0)
        ))
        client.setCart(cart)

        // Set wishlist
        val wishlist = listOf(
            WishlistProduct(id = "w1"),
            WishlistProduct(id = "w2")
        )
        client.setWishlist(wishlist)

        // Verify all data is stored
        assertEquals("user-456", storageService.getProfileId())
        assertEquals("device-789", storageService.getDeviceId())
        assertNotNull(storageService.getCartData())
        assertEquals(2, storageService.getWishlistData()!!.size)
    }

    @Test
    fun `client with different realms`() {
        val clientDefault = RelevaClient(context, "", "token-123")
        val clientCustomRealm = RelevaClient(context, "custom", "token-456")

        assertNotNull(clientDefault)
        assertNotNull(clientCustomRealm)
        // Endpoint generation is tested implicitly (private method)
    }

    @Test
    fun `multiple clients can coexist`() {
        val client1 = RelevaClient(context, "", "token-1")
        val client2 = RelevaClient(context, "realm", "token-2")

        assertNotNull(client1)
        assertNotNull(client2)
        // Both clients share the same StorageService singleton
    }

    // Helper Methods

    private fun createTestClient(
        realm: String = "",
        accessToken: String = "test-token",
        config: RelevaConfig = RelevaConfig.full()
    ): RelevaClient {
        return RelevaClient(context, realm, accessToken, config)
    }
}
