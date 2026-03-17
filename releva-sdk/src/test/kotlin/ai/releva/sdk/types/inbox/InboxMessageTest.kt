package ai.releva.sdk.types.inbox

import org.junit.Assert.*
import org.junit.Test

class InboxMessageTest {

    @Test
    fun `fromMap parses full message`() {
        val map = mapOf<String, Any?>(
            "id" to "msg-1",
            "title" to "Welcome!",
            "design" to mapOf("body" to mapOf("values" to emptyMap<String, Any?>())),
            "read" to false,
            "createdAt" to "2024-01-15T10:30:00.000Z",
            "inboxMessageId" to 42
        )

        val msg = InboxMessage.fromMap(map)
        assertEquals("msg-1", msg.id)
        assertEquals("Welcome!", msg.title)
        assertFalse(msg.read)
        assertEquals(42, msg.inboxMessageId)
        assertNotNull(msg.createdAt)
    }

    @Test
    fun `fromMap with minimal data uses defaults`() {
        val msg = InboxMessage.fromMap(mapOf("createdAt" to "2024-01-01T00:00:00Z"))
        assertEquals("", msg.id)
        assertEquals("", msg.title)
        assertFalse(msg.read)
        assertEquals(0, msg.inboxMessageId)
    }

    @Test
    fun `toMap roundtrips correctly`() {
        val original = InboxMessage(
            id = "m1",
            title = "Test",
            design = mapOf("key" to "value"),
            read = true,
            createdAt = java.util.Date(1700000000000L),
            inboxMessageId = 99
        )

        val restored = InboxMessage.fromMap(original.toMap())
        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.read, restored.read)
        assertEquals(original.inboxMessageId, restored.inboxMessageId)
    }

    @Test
    fun `read field can be changed via copy`() {
        val msg = InboxMessage(
            id = "m1", title = "T", design = emptyMap(),
            read = false, createdAt = java.util.Date(), inboxMessageId = 1
        )
        assertFalse(msg.read)
        val readMsg = msg.copy(read = true)
        assertTrue(readMsg.read)
        // original is unchanged
        assertFalse(msg.read)
    }
}
