package ai.releva.sdk.types.inbox

import org.junit.Assert.*
import org.junit.Test

class InboxStateTest {

    @Test
    fun `default state has expected values`() {
        val state = InboxState()
        assertTrue(state.messages.isEmpty())
        assertEquals(0, state.unreadCount)
        assertNull(state.nextCursor)
        assertFalse(state.isLoading)
        assertTrue(state.hasMore)
        assertNull(state.lastFetchTime)
    }

    @Test
    fun `isStale returns true when no lastFetchTime`() {
        val state = InboxState()
        assertTrue(state.isStale)
    }

    @Test
    fun `isStale returns true when older than 5 minutes`() {
        val sixMinutesAgo = System.currentTimeMillis() - 6 * 60 * 1000
        val state = InboxState(lastFetchTime = sixMinutesAgo)
        assertTrue(state.isStale)
    }

    @Test
    fun `isStale returns false when within 5 minutes`() {
        val twoMinutesAgo = System.currentTimeMillis() - 2 * 60 * 1000
        val state = InboxState(lastFetchTime = twoMinutesAgo)
        assertFalse(state.isStale)
    }

    @Test
    fun `isStale returns false when just fetched`() {
        val state = InboxState(lastFetchTime = System.currentTimeMillis())
        assertFalse(state.isStale)
    }

    @Test
    fun `copy preserves values`() {
        val msg = InboxMessage(
            id = "m1", title = "T", design = emptyMap(),
            read = false, createdAt = java.util.Date(), inboxMessageId = 1
        )
        val state = InboxState(
            messages = listOf(msg),
            unreadCount = 5,
            nextCursor = "cursor-1",
            isLoading = false,
            hasMore = true,
            lastFetchTime = 12345L
        )

        val copy = state.copy(unreadCount = 4)
        assertEquals(4, copy.unreadCount)
        assertEquals(1, copy.messages.size)
        assertEquals("cursor-1", copy.nextCursor)
        assertEquals(12345L, copy.lastFetchTime)
    }
}
