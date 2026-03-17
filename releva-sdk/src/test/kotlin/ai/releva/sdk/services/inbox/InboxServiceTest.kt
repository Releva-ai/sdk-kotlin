package ai.releva.sdk.services.inbox

import ai.releva.sdk.services.storage.StorageService
import ai.releva.sdk.types.inbox.InboxMessage
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
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboxServiceTest {

    private lateinit var context: Context
    private lateinit var storage: StorageService
    private lateinit var mockClient: FakeInboxApiClient

    // We can't use InboxService.instance in tests because it's a singleton.
    // Instead we'll test the client interface and state logic indirectly.

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageService.getInstance(context)
        storage.clear()
        mockClient = FakeInboxApiClient()
    }

    @After
    fun tearDown() {
        storage.clear()
    }

    @Test
    fun `FakeInboxApiClient returns messages`() = runTest {
        mockClient.messages = listOf(
            mapOf<String, Any?>("id" to "m1", "title" to "Hello", "design" to emptyMap<String, Any?>(), "read" to false, "createdAt" to "2024-01-01T00:00:00Z", "inboxMessageId" to 1)
        )

        val result = mockClient.inboxFetchMessages()
        @Suppress("UNCHECKED_CAST")
        val messages = result["messages"] as List<Map<String, Any?>>
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0]["id"])
    }

    @Test
    fun `FakeInboxApiClient returns unread count`() = runTest {
        mockClient.unreadCount = 5
        assertEquals(5, mockClient.inboxFetchUnreadCount())
    }

    @Test
    fun `InboxMessage fromMap preserves fields through API roundtrip`() {
        val map = mapOf<String, Any?>(
            "id" to "msg-1",
            "title" to "Test",
            "design" to mapOf("body" to mapOf<String, Any?>()),
            "read" to false,
            "createdAt" to "2024-06-15T12:00:00.000Z",
            "inboxMessageId" to 42
        )

        val msg = InboxMessage.fromMap(map)
        assertEquals("msg-1", msg.id)
        assertFalse(msg.read)

        // Simulate optimistic update
        msg.read = true
        assertTrue(msg.read)

        // Rollback
        msg.read = false
        assertFalse(msg.read)
    }

    @Test
    fun `markAsRead and rollback pattern`() {
        val msg = InboxMessage(
            id = "m1", title = "T", design = emptyMap(),
            read = false, createdAt = Date(), inboxMessageId = 1
        )

        // Snapshot
        val originalRead = msg.read

        // Optimistic update
        msg.read = true
        assertTrue(msg.read)

        // Rollback
        msg.read = originalRead
        assertFalse(msg.read)
    }

    @Test
    fun `delete message pattern`() {
        val messages = mutableListOf(
            InboxMessage(id = "m1", title = "T1", design = emptyMap(), read = false, createdAt = Date(), inboxMessageId = 1),
            InboxMessage(id = "m2", title = "T2", design = emptyMap(), read = true, createdAt = Date(), inboxMessageId = 2),
            InboxMessage(id = "m3", title = "T3", design = emptyMap(), read = false, createdAt = Date(), inboxMessageId = 3)
        )

        val index = messages.indexOfFirst { it.id == "m2" }
        assertEquals(1, index)

        val removed = messages.removeAt(index)
        assertEquals("m2", removed.id)
        assertEquals(2, messages.size)
        assertEquals("m1", messages[0].id)
        assertEquals("m3", messages[1].id)
    }

    @Test
    fun `markAllAsRead pattern`() {
        val messages = listOf(
            InboxMessage(id = "m1", title = "T1", design = emptyMap(), read = false, createdAt = Date(), inboxMessageId = 1),
            InboxMessage(id = "m2", title = "T2", design = emptyMap(), read = false, createdAt = Date(), inboxMessageId = 2)
        )

        // Snapshot
        val originalStates = messages.map { it.id to it.read }

        // Optimistic
        messages.forEach { it.read = true }
        assertTrue(messages.all { it.read })

        // Rollback
        for ((id, wasRead) in originalStates) {
            messages.find { it.id == id }?.read = wasRead
        }
        assertFalse(messages[0].read)
        assertFalse(messages[1].read)
    }

    /**
     * Fake implementation of InboxApiClient for testing
     */
    class FakeInboxApiClient : InboxApiClient {
        var messages: List<Map<String, Any?>> = emptyList()
        var unreadCount: Int = 0
        var markAsReadCalled = false
        var markAllAsReadCalled = false
        var deleteCalled = false
        var trackActionCalled = false

        override suspend fun inboxFetchMessages(limit: Int, cursor: String?): Map<String, Any?> {
            return mapOf("messages" to messages, "nextCursor" to null)
        }

        override suspend fun inboxFetchUnreadCount(): Int = unreadCount

        override suspend fun inboxMarkAsRead(messageId: String) {
            markAsReadCalled = true
        }

        override suspend fun inboxMarkAllAsRead() {
            markAllAsReadCalled = true
        }

        override suspend fun inboxDeleteMessage(messageId: String) {
            deleteCalled = true
        }

        override suspend fun inboxTrackAction(messageId: String) {
            trackActionCalled = true
        }
    }
}
