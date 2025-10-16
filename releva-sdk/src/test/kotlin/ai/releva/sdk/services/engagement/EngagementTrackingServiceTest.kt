package ai.releva.sdk.services.engagement

import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EngagementTrackingServiceTest {

    private lateinit var context: Context
    private lateinit var storageService: StorageService
    private lateinit var engagementService: EngagementTrackingService
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageService = StorageService.getInstance(context)
        storageService.clear()
        engagementService = EngagementTrackingService(context)
    }

    @After
    fun tearDown() {
        engagementService.dispose()
        storageService.clear()
    }

    // Message Recognition Tests

    @Test
    fun `isRelevaMessage returns true for Releva notification`() {
        val data = mapOf("click_action" to "RELEVA_NOTIFICATION_CLICK")

        assertTrue(engagementService.isRelevaMessage(data))
    }

    @Test
    fun `isRelevaMessage returns false for non-Releva notification`() {
        val data = mapOf("click_action" to "OTHER_ACTION")

        assertFalse(engagementService.isRelevaMessage(data))
    }

    @Test
    fun `isRelevaMessage returns false when click_action is missing`() {
        val data = mapOf("other_key" to "value")

        assertFalse(engagementService.isRelevaMessage(data))
    }

    @Test
    fun `isRelevaMessage returns false for empty data`() {
        val data = emptyMap<String, String>()

        assertFalse(engagementService.isRelevaMessage(data))
    }

    // Pending Events Management Tests

    @Test
    fun `initial pending events count is zero`() {
        assertEquals(0, engagementService.getPendingEventsCount())
    }

    @Test
    fun `getPendingEvents returns empty list initially`() {
        val events = engagementService.getPendingEvents()

        assertNotNull(events)
        assertEquals(0, events.size)
    }

    @Test
    fun `trackEngagement adds event for Releva message`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback1"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(1, engagementService.getPendingEventsCount())
        assertEquals("https://example.com/callback1", engagementService.getPendingEvents()[0])
    }

    @Test
    fun `trackEngagement ignores non-Releva message`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "OTHER_ACTION",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(0, engagementService.getPendingEventsCount())
    }

    @Test
    fun `trackEngagement ignores Releva message without callbackUrl`() = runTest(testDispatcher) {
        val data = mapOf("click_action" to "RELEVA_NOTIFICATION_CLICK")

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(0, engagementService.getPendingEventsCount())
    }

    @Test
    fun `trackEngagement adds multiple events`() = runTest(testDispatcher) {
        val data1 = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback1"
        )
        val data2 = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback2"
        )
        val data3 = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback3"
        )

        engagementService.trackEngagement(data1)
        engagementService.trackEngagement(data2)
        engagementService.trackEngagement(data3)
        advanceUntilIdle()

        assertEquals(3, engagementService.getPendingEventsCount())
        val events = engagementService.getPendingEvents()
        assertTrue(events.contains("https://example.com/callback1"))
        assertTrue(events.contains("https://example.com/callback2"))
        assertTrue(events.contains("https://example.com/callback3"))
    }

    @Test
    fun `clearPendingEvents removes all events`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()
        assertEquals(1, engagementService.getPendingEventsCount())

        engagementService.clearPendingEvents()

        assertEquals(0, engagementService.getPendingEventsCount())
        assertEquals(0, engagementService.getPendingEvents().size)
    }

    @Test
    fun `clearPendingEvents on empty list does not throw`() {
        engagementService.clearPendingEvents()

        assertEquals(0, engagementService.getPendingEventsCount())
    }

    // Storage Persistence Tests

    @Test
    fun `trackEngagement persists events to storage`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        // Verify events are saved to storage
        val storedEvents = storageService.getPendingEngagementEvents()
        assertNotNull(storedEvents)
        assertEquals(1, storedEvents!!.size)
        assertEquals("https://example.com/callback", storedEvents[0])
    }

    @Test
    fun `clearPendingEvents clears storage`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        // Verify event is in storage
        assertNotNull(storageService.getPendingEngagementEvents())

        engagementService.clearPendingEvents()

        // Verify storage is cleared
        val storedEvents = storageService.getPendingEngagementEvents()
        assertNotNull(storedEvents)
        assertEquals(0, storedEvents!!.size)
    }

    @Test
    fun `initialize loads pending events from storage`() {
        // Manually add events to storage
        storageService.setPendingEngagementEvents(
            listOf(
                "https://example.com/event1",
                "https://example.com/event2"
            )
        )

        // Create new service instance (will call loadPendingEvents)
        val newService = EngagementTrackingService(context)
        newService.initialize()

        assertEquals(2, newService.getPendingEventsCount())
        val events = newService.getPendingEvents()
        assertTrue(events.contains("https://example.com/event1"))
        assertTrue(events.contains("https://example.com/event2"))

        newService.dispose()
    }

    @Test
    fun `initialize handles empty storage`() {
        // Ensure storage is empty
        storageService.setPendingEngagementEvents(emptyList())

        val newService = EngagementTrackingService(context)
        newService.initialize()

        assertEquals(0, newService.getPendingEventsCount())

        newService.dispose()
    }

    @Test
    fun `initialize handles null storage`() {
        // Ensure storage returns null
        storageService.clear()

        val newService = EngagementTrackingService(context)
        newService.initialize()

        assertEquals(0, newService.getPendingEventsCount())

        newService.dispose()
    }

    // State Management Tests

    @Test
    fun `getPendingEvents returns immutable copy`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        val events1 = engagementService.getPendingEvents()
        val events2 = engagementService.getPendingEvents()

        // Should be different list instances
        assertNotSame(events1, events2)
        // But with same content
        assertEquals(events1, events2)
    }

    @Test
    fun `modifying returned pending events list does not affect internal state`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        val events = engagementService.getPendingEvents().toMutableList()
        events.add("https://malicious.com/event")

        // Internal state should not be affected
        assertEquals(1, engagementService.getPendingEventsCount())
        assertFalse(engagementService.getPendingEvents().contains("https://malicious.com/event"))
    }

    // Integration Tests

    @Test
    fun `track multiple events from different sources`() = runTest(testDispatcher) {
        // Track various events
        engagementService.trackEngagement(mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/click1"
        ))
        engagementService.trackEngagement(mapOf(
            "click_action" to "OTHER_ACTION",
            "callbackUrl" to "https://example.com/other"
        ))
        engagementService.trackEngagement(mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/click2"
        ))
        engagementService.trackEngagement(mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK"
            // No callbackUrl
        ))
        advanceUntilIdle()

        // Only Releva messages with callbackUrl should be tracked
        assertEquals(2, engagementService.getPendingEventsCount())

        val events = engagementService.getPendingEvents()
        assertTrue(events.contains("https://example.com/click1"))
        assertTrue(events.contains("https://example.com/click2"))
        assertFalse(events.contains("https://example.com/other"))
    }

    @Test
    fun `service lifecycle - initialize, track, clear, dispose`() = runTest(testDispatcher) {
        // Initialize
        engagementService.initialize()
        assertEquals(0, engagementService.getPendingEventsCount())

        // Track events
        engagementService.trackEngagement(mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/event"
        ))
        advanceUntilIdle()
        assertEquals(1, engagementService.getPendingEventsCount())

        // Clear
        engagementService.clearPendingEvents()
        assertEquals(0, engagementService.getPendingEventsCount())

        // Dispose
        engagementService.dispose()
        // After dispose, service should still be in valid state (just no background jobs)
        assertEquals(0, engagementService.getPendingEventsCount())
    }

    // Edge Cases

    @Test
    fun `handle null callbackUrl value`() = runTest(testDispatcher) {
        // Test with no callbackUrl in the map (simulating null value)
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(0, engagementService.getPendingEventsCount())
    }

    @Test
    fun `handle empty callbackUrl`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to ""
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        // Empty string is still added (service doesn't validate URL format)
        assertEquals(1, engagementService.getPendingEventsCount())
    }

    @Test
    fun `handle special characters in callbackUrl`() = runTest(testDispatcher) {
        val specialUrl = "https://example.com/callback?param=value&special=!@#$%^&*()"
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to specialUrl
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(1, engagementService.getPendingEventsCount())
        assertEquals(specialUrl, engagementService.getPendingEvents()[0])
    }

    @Test
    fun `dispose can be called multiple times safely`() {
        engagementService.dispose()
        engagementService.dispose()
        engagementService.dispose()

        // Should not throw exception
        assertEquals(0, engagementService.getPendingEventsCount())
    }

    @Test
    fun `data map with extra fields does not affect tracking`() = runTest(testDispatcher) {
        val data = mapOf(
            "click_action" to "RELEVA_NOTIFICATION_CLICK",
            "callbackUrl" to "https://example.com/callback",
            "extra_field_1" to "value1",
            "extra_field_2" to "value2",
            "notification_title" to "Test Title"
        )

        engagementService.trackEngagement(data)
        advanceUntilIdle()

        assertEquals(1, engagementService.getPendingEventsCount())
        assertEquals("https://example.com/callback", engagementService.getPendingEvents()[0])
    }
}
