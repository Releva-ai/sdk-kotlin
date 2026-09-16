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
import ai.releva.sdk.types.tracking.PushRequest
import ai.releva.sdk.types.wishlist.WishlistProduct
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelevaClientTest {

    private lateinit var context: Context
    private lateinit var storageService: StorageService
    private var mockWebServer: MockWebServer? = null
    private lateinit var client: RelevaClient

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageService = StorageService.getInstance(context)
        storageService.clear()

        // Note: most tests here focus on state management, initialization logic and
        // request preparation; the ones that need to inspect a request body point the
        // client at a MockWebServer via setEndpointOverride().
    }

    @After
    fun tearDown() {
        mockWebServer?.shutdown()
        mockWebServer = null
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

    // Merge Profile ID Tests

    @Test
    fun `merge profile id survives a client that dies before a successful push`() = runTest {
        storageService.setDeviceId("device-1")
        val firstClient = createTestClient()
        firstClient.setProfileId("profile-A")
        firstClient.setProfileId("profile-B")
        // firstClient is dropped without ever pushing successfully

        val server = startPushServer()
        val secondClient = createTestClient()
        secondClient.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        secondClient.push(PushRequest())

        val body = server.takeRequest().body.readUtf8()
        assertEquals(listOf("profile-A"), mergeProfileIdsOf(body))
        // The new client never saw the profile change, but the ids it is carrying say one
        // happened, so the flag has to agree with them.
        assertTrue(JSONObject(body).getJSONObject("context").getBoolean("profileChanged"))
    }

    @Test
    fun `successful push clears the merge list from storage`() = runTest {
        storageService.setDeviceId("device-1")
        val server = startPushServer()

        val client = createTestClient()
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        client.push(PushRequest())

        assertEquals(listOf("profile-A"), mergeProfileIdsOf(server.takeRequest().body.readUtf8()))
        assertEquals(emptyList<String>(), storageService.getMergeProfileIds())

        // A later client over the same storage must not resend it
        val laterClient = createTestClient()
        laterClient.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        laterClient.push(PushRequest())

        val laterBody = server.takeRequest().body.readUtf8()
        assertEquals(emptyList<String>(), mergeProfileIdsOf(laterBody))
        // An empty queue must not fabricate a profile change either.
        assertFalse(JSONObject(laterBody).getJSONObject("context").getBoolean("profileChanged"))
    }

    @Test
    fun `a failed push leaves the merge queue persisted for the next attempt`() = runTest {
        storageService.setDeviceId("device-1")
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(500).setBody("boom")
        }
        mockWebServer = server

        val client = createTestClient()
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        // The push fails (non-200): the clear block at the end of push() is never reached,
        // so the queue must still be there afterwards, both in this client and a fresh one.
        try {
            client.push(PushRequest())
            fail("Expected push() to throw on a 500 response")
        } catch (e: Exception) {
            // expected
        }

        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        // Drain every request the failed push produced, however many that is, so the
        // takeRequest() at the end of this test is unambiguously the later client's and
        // cannot silently become a retry of the push above.
        var drained = 0
        while (server.takeRequest(100, TimeUnit.MILLISECONDS) != null) {
            drained++
        }
        assertTrue("expected the failed push to have reached the server", drained > 0)

        val laterClient = createTestClient()
        laterClient.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("{}")
        }
        laterClient.push(PushRequest())

        assertEquals(listOf("profile-A"), mergeProfileIdsOf(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun `setProfileId with skipMerge does not queue the id it is replacing`() = runTest {
        val client = createTestClient()

        client.setProfileId("profile-A")
        client.setProfileId("anonymous-B", skipMergeWithPreviousProfileId = true)

        assertEquals(emptyList<String>(), storageService.getMergeProfileIds())
    }

    @Test
    fun `logging out discards a queue that could only be delivered to the wrong profile`() = runTest {
        // A -> B is queued and undelivered (the device was offline), then the host logs out.
        // Keeping the queue looks like preserving that link, and is not: push sends
        // mergeProfileIds alongside profile.id read from storage AT PUSH TIME, so after the
        // logout the body would be profile.id = anonymous-C with mergeProfileIds = ["A"] —
        // merging the signed-out user into the anonymous session rather than into B, which is
        // no longer stored anywhere. A -> B is already unrecoverable at this point; the only
        // question is whether A gets delivered to the wrong profile, and it must not.
        val client = createTestClient()

        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        client.setProfileId("anonymous-C", skipMergeWithPreviousProfileId = true)

        assertEquals(
            "a queued id must not outlive the profile it was queued against",
            emptyList<String>(), storageService.getMergeProfileIds()
        )
    }

    @Test
    fun `skipMerge clears the queue even when the profile id is unchanged`() = runTest {
        // The changed-id guard below would skip this transition entirely, but a caller passing
        // the flag still means "cancel anything pending" — and the clear sits above that guard
        // so it happens either way.
        val client = createTestClient()

        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        client.setProfileId("profile-B", skipMergeWithPreviousProfileId = true)

        assertEquals(emptyList<String>(), storageService.getMergeProfileIds())
    }

    @Test
    fun `the same previous profile id is not queued for merging twice`() = runTest {
        val client = createTestClient()

        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        client.setProfileId("profile-A")
        client.setProfileId("profile-B")

        assertEquals(listOf("profile-A", "profile-B"), storageService.getMergeProfileIds())
    }

    @Test
    fun `a successful push token registration leaves the persisted merge queue intact`() = runTest {
        storageService.setDeviceId("device-1")
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(202).setBody("{}")
        }
        mockWebServer = server

        val client = createTestClient()
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        client.setProfileId("profile-A")
        client.setProfileId("profile-B")
        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        // registerPushToken's request body never carries mergeProfileIds, so a successful
        // call here must not discard the queue a later push() would still need to send.
        client.registerPushToken(DeviceType.ANDROID, "token-xyz")

        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())
    }

    @Test
    fun `a successful push clears only the ids it sent, not ones queued while it was in flight`() = runTest {
        storageService.setDeviceId("device-1")
        val client = createTestClient()

        // Recorded rather than asserted inside dispatch(): MockWebServer swallows a throw from
        // its dispatcher thread, which would surface as an unrelated transport failure.
        var queueMidFlight: List<String>? = null
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Runs on the server's thread, after push() built and sent the body but before
                // it sees a response — so this id is queued while the request is in flight and
                // was never on the wire.
                runBlocking { client.setProfileId("profile-C") } // queues "profile-B"
                queueMidFlight = storageService.getMergeProfileIds()
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        mockWebServer = server

        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))
        client.setProfileId("profile-A")
        client.setProfileId("profile-B") // queues "profile-A"
        assertEquals(listOf("profile-A"), storageService.getMergeProfileIds())

        client.push(PushRequest())

        assertEquals(listOf("profile-A", "profile-B"), queueMidFlight)
        // "profile-A" was sent and is removed; "profile-B" was queued mid-flight and survives
        // for the next push to send.
        assertEquals(listOf("profile-B"), storageService.getMergeProfileIds())
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

    @Test
    fun `registerPushToken sends nothing when push notifications are disabled`() = runTest {
        storageService.setDeviceId("device-1")
        storageService.setProfileId("profile-1")
        val server = startTokenServer()

        // trackingOnly is the preset this was found under: it says the app does not do push,
        // so no token may reach the backend.
        val client = createTestClient(config = RelevaConfig.trackingOnly())
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))

        client.registerPushToken(DeviceType.ANDROID, "token-xyz")

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `registerPushToken sends one request when push notifications are enabled`() = runTest {
        storageService.setDeviceId("device-1")
        storageService.setProfileId("profile-1")
        val server = startTokenServer()

        val client = createTestClient(config = RelevaConfig.pushOnly())
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))

        client.registerPushToken(DeviceType.ANDROID, "token-xyz")

        assertEquals(1, server.requestCount)
        assertEquals("/api/v0/appPush/tokens", server.takeRequest().path)
    }

    @Test
    fun `registerPushToken still throws on a missing deviceId or profileId`() = runTest {
        val server = startTokenServer()
        val client = createTestClient(config = RelevaConfig.pushOnly())
        client.setEndpointOverride(server.url("/").toString().trimEnd('/'))

        try {
            client.registerPushToken(DeviceType.ANDROID, "token-xyz")
            fail("Expected registerPushToken() to throw without a deviceId")
        } catch (e: Exception) {
            // expected
        }

        storageService.setDeviceId("device-1")
        try {
            client.registerPushToken(DeviceType.ANDROID, "token-xyz")
            fail("Expected registerPushToken() to throw without a profileId")
        } catch (e: Exception) {
            // expected
        }

        assertEquals(0, server.requestCount)
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

    /**
     * Starts a server that answers every push with an empty (but valid) response body.
     */
    private fun startPushServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("{}")
        }
        mockWebServer = server
        return server
    }

    /**
     * Starts a server that accepts every push-token registration (the endpoint answers 202).
     */
    private fun startTokenServer(): MockWebServer {
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(202).setBody("{}")
        }
        mockWebServer = server
        return server
    }

    private fun mergeProfileIdsOf(requestBody: String): List<String> {
        val ids = JSONObject(requestBody)
            .getJSONObject("context")
            .getJSONArray("mergeProfileIds")
        return List(ids.length()) { ids.getString(it) }
    }
}
