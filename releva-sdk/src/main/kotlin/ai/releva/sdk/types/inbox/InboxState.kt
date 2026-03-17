package ai.releva.sdk.types.inbox

/**
 * Immutable state representing the current inbox
 */
data class InboxState(
    val messages: List<InboxMessage> = emptyList(),
    val unreadCount: Int = 0,
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val lastFetchTime: Long? = null  // epoch millis
) {
    val isStale: Boolean
        get() {
            if (lastFetchTime == null) return true
            return System.currentTimeMillis() - lastFetchTime > STALE_THRESHOLD_MS
        }

    companion object {
        const val STALE_THRESHOLD_MS = 5 * 60 * 1000L  // 5 minutes
    }
}
