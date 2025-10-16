package ai.releva.sdk.services.storage

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageServiceTest {

    private lateinit var context: Context
    private lateinit var storageService: StorageService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageService = StorageService.getInstance(context)
        // Clear all data before each test
        storageService.clear()
    }

    // Singleton Pattern Tests

    @Test
    fun `singleton returns same instance`() {
        val instance1 = StorageService.getInstance(context)
        val instance2 = StorageService.getInstance(context)

        assertSame(instance1, instance2)
    }

    // Profile and Device Management Tests

    @Test
    fun `set and get profile id`() {
        storageService.setProfileId("profile-123")

        assertEquals("profile-123", storageService.getProfileId())
    }

    @Test
    fun `get profile id returns null when not set`() {
        assertNull(storageService.getProfileId())
    }

    @Test
    fun `set and get device id`() {
        storageService.setDeviceId("device-abc")

        assertEquals("device-abc", storageService.getDeviceId())
    }

    @Test
    fun `get device id returns null when not set`() {
        assertNull(storageService.getDeviceId())
    }

    @Test
    fun `overwrite profile id with new value`() {
        storageService.setProfileId("profile-1")
        storageService.setProfileId("profile-2")

        assertEquals("profile-2", storageService.getProfileId())
    }

    // Session Management Tests

    @Test
    fun `set and get session id`() {
        storageService.setSessionId("session-xyz")

        assertEquals("session-xyz", storageService.getSessionId())
    }

    @Test
    fun `get session id returns null when not set`() {
        assertNull(storageService.getSessionId())
    }

    @Test
    fun `set and get session timestamp`() {
        val timestamp = System.currentTimeMillis()
        storageService.setSessionTimestamp(timestamp)

        assertEquals(timestamp, storageService.getSessionTimestamp())
    }

    @Test
    fun `get session timestamp returns null when not set`() {
        assertNull(storageService.getSessionTimestamp())
    }

    // Cart Management Tests

    @Test
    fun `set and get cart data`() {
        val cartJson = """{"products":[{"id":"p1","price":99.99}]}"""
        storageService.setCartData(cartJson)

        assertEquals(cartJson, storageService.getCartData())
    }

    @Test
    fun `get cart data returns null when not set`() {
        assertNull(storageService.getCartData())
    }

    @Test
    fun `overwrite cart data with new value`() {
        storageService.setCartData("""{"products":[{"id":"p1"}]}""")
        storageService.setCartData("""{"products":[{"id":"p2"}]}""")

        assertEquals("""{"products":[{"id":"p2"}]}""", storageService.getCartData())
    }

    // Wishlist Management Tests

    @Test
    fun `set and get wishlist data`() {
        val wishlist = listOf("product-1", "product-2", "product-3")
        storageService.setWishlistData(wishlist)

        val retrieved = storageService.getWishlistData()
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.size)
        assertEquals("product-1", retrieved[0])
        assertEquals("product-2", retrieved[1])
        assertEquals("product-3", retrieved[2])
    }

    @Test
    fun `get wishlist data returns null when not set`() {
        assertNull(storageService.getWishlistData())
    }

    @Test
    fun `set empty wishlist`() {
        storageService.setWishlistData(emptyList())

        val retrieved = storageService.getWishlistData()
        assertNotNull(retrieved)
        assertEquals(0, retrieved!!.size)
    }

    @Test
    fun `overwrite wishlist data`() {
        storageService.setWishlistData(listOf("p1", "p2"))
        storageService.setWishlistData(listOf("p3", "p4", "p5"))

        val retrieved = storageService.getWishlistData()
        assertEquals(3, retrieved!!.size)
        assertEquals("p3", retrieved[0])
    }

    @Test
    fun `get wishlist returns null for malformed json`() {
        // Manually set invalid JSON to test error handling
        storageService.setString("wishlist_data", "invalid json {[")

        assertNull(storageService.getWishlistData())
    }

    // Analytics Data Tests

    @Test
    fun `set and get pending message events`() {
        val events = listOf("event1", "event2", "event3")
        storageService.setPendingMessageEvents(events)

        val retrieved = storageService.getPendingMessageEvents()
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.size)
        assertEquals("event1", retrieved[0])
        assertEquals("event2", retrieved[1])
        assertEquals("event3", retrieved[2])
    }

    @Test
    fun `get pending message events returns null when not set`() {
        assertNull(storageService.getPendingMessageEvents())
    }

    @Test
    fun `set empty pending message events`() {
        storageService.setPendingMessageEvents(emptyList())

        val retrieved = storageService.getPendingMessageEvents()
        assertNotNull(retrieved)
        assertEquals(0, retrieved!!.size)
    }

    @Test
    fun `get pending message events returns null for malformed json`() {
        storageService.setString("pending_message_events", "not valid json")

        assertNull(storageService.getPendingMessageEvents())
    }

    // Engagement Data Tests

    @Test
    fun `set and get pending engagement events`() {
        val events = listOf("engage1", "engage2")
        storageService.setPendingEngagementEvents(events)

        val retrieved = storageService.getPendingEngagementEvents()
        assertNotNull(retrieved)
        assertEquals(2, retrieved!!.size)
        assertEquals("engage1", retrieved[0])
        assertEquals("engage2", retrieved[1])
    }

    @Test
    fun `get pending engagement events returns null when not set`() {
        assertNull(storageService.getPendingEngagementEvents())
    }

    @Test
    fun `set empty pending engagement events`() {
        storageService.setPendingEngagementEvents(emptyList())

        val retrieved = storageService.getPendingEngagementEvents()
        assertNotNull(retrieved)
        assertEquals(0, retrieved!!.size)
    }

    @Test
    fun `get pending engagement events returns null for malformed json`() {
        storageService.setString("pending_engagement_events", "{{invalid}}")

        assertNull(storageService.getPendingEngagementEvents())
    }

    // Settings Data Tests

    @Test
    fun `set and get last message fetch timestamp`() {
        val timestamp = System.currentTimeMillis()
        storageService.setLastMessageFetch(timestamp)

        assertEquals(timestamp, storageService.getLastMessageFetch())
    }

    @Test
    fun `get last message fetch returns null when not set`() {
        assertNull(storageService.getLastMessageFetch())
    }

    // Generic Methods Tests

    @Test
    fun `set and get string value`() {
        storageService.setString("custom_key", "custom_value")

        assertEquals("custom_value", storageService.getString("custom_key"))
    }

    @Test
    fun `get string returns null for non-existent key`() {
        assertNull(storageService.getString("non_existent"))
    }

    @Test
    fun `set and get int value`() {
        storageService.setInt("count", 42)

        assertEquals(42, storageService.getInt("count"))
    }

    @Test
    fun `get int returns null for non-existent key`() {
        assertNull(storageService.getInt("non_existent"))
    }

    @Test
    fun `set and get long value`() {
        storageService.setLong("timestamp", 1234567890L)

        assertEquals(1234567890L, storageService.getLong("timestamp"))
    }

    @Test
    fun `get long returns null for non-existent key`() {
        assertNull(storageService.getLong("non_existent"))
    }

    @Test
    fun `set and get boolean value`() {
        storageService.setBoolean("flag", true)

        assertEquals(true, storageService.getBoolean("flag"))
    }

    @Test
    fun `get boolean returns null for non-existent key`() {
        assertNull(storageService.getBoolean("non_existent"))
    }

    @Test
    fun `set boolean false value`() {
        storageService.setBoolean("flag", false)

        assertEquals(false, storageService.getBoolean("flag"))
    }

    // Key Management Tests

    @Test
    fun `contains key returns true for existing key`() {
        storageService.setString("test_key", "test_value")

        assertTrue(storageService.containsKey("test_key"))
    }

    @Test
    fun `contains key returns false for non-existent key`() {
        assertFalse(storageService.containsKey("non_existent"))
    }

    @Test
    fun `remove key deletes value`() {
        storageService.setString("to_remove", "value")
        assertTrue(storageService.containsKey("to_remove"))

        storageService.remove("to_remove")

        assertFalse(storageService.containsKey("to_remove"))
        assertNull(storageService.getString("to_remove"))
    }

    @Test
    fun `clear removes all data`() {
        // Set multiple values
        storageService.setProfileId("profile")
        storageService.setDeviceId("device")
        storageService.setSessionId("session")
        storageService.setString("custom", "value")
        storageService.setInt("count", 10)

        // Verify data exists
        assertNotNull(storageService.getProfileId())
        assertNotNull(storageService.getDeviceId())
        assertTrue(storageService.containsKey("custom"))

        // Clear all
        storageService.clear()

        // Verify all data removed
        assertNull(storageService.getProfileId())
        assertNull(storageService.getDeviceId())
        assertNull(storageService.getSessionId())
        assertNull(storageService.getString("custom"))
        assertNull(storageService.getInt("count"))
        assertFalse(storageService.containsKey("custom"))
    }

    // Integration Tests

    @Test
    fun `multiple operations in sequence`() {
        // Set various data
        storageService.setProfileId("user-123")
        storageService.setDeviceId("device-abc")
        storageService.setSessionId("session-xyz")
        storageService.setSessionTimestamp(1000000L)
        storageService.setWishlistData(listOf("p1", "p2"))
        storageService.setString("custom", "data")

        // Verify all data persists
        assertEquals("user-123", storageService.getProfileId())
        assertEquals("device-abc", storageService.getDeviceId())
        assertEquals("session-xyz", storageService.getSessionId())
        assertEquals(1000000L, storageService.getSessionTimestamp())
        assertEquals(2, storageService.getWishlistData()!!.size)
        assertEquals("data", storageService.getString("custom"))

        // Remove some data
        storageService.remove("custom")
        storageService.setWishlistData(emptyList())

        // Verify selective removal
        assertEquals("user-123", storageService.getProfileId())
        assertNull(storageService.getString("custom"))
        assertEquals(0, storageService.getWishlistData()!!.size)
    }

    @Test
    fun `data persists across getInstance calls`() {
        storageService.setProfileId("persist-test")
        storageService.setInt("counter", 99)

        // Get new instance (should be same singleton)
        val newInstance = StorageService.getInstance(context)

        assertEquals("persist-test", newInstance.getProfileId())
        assertEquals(99, newInstance.getInt("counter"))
    }
}
