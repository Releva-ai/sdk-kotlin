package ai.releva.sdk.services.inbox

import ai.releva.sdk.services.storage.StorageService
import ai.releva.sdk.types.inbox.InboxMessage
import ai.releva.sdk.types.inbox.InboxState
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interface for inbox API calls. Implemented by RelevaClient to avoid circular dependency.
 */
interface InboxApiClient {
    suspend fun inboxFetchMessages(limit: Int = 20, cursor: String? = null): Map<String, Any?>
    suspend fun inboxFetchUnreadCount(): Int
    suspend fun inboxMarkAsRead(messageId: String)
    suspend fun inboxMarkAllAsRead()
    suspend fun inboxDeleteMessage(messageId: String)
    suspend fun inboxTrackAction(messageId: String)
}

/**
 * Manages inbox state with optimistic updates, pagination, caching, and lifecycle-aware refresh.
 */
class InboxService private constructor() : DefaultLifecycleObserver {

    private var client: InboxApiClient? = null
    private var storage: StorageService? = null
    private var initialized = false

    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateMutex = Mutex()

    /**
     * Initialize with a client reference and storage service.
     */
    fun initialize(client: InboxApiClient, storage: StorageService) {
        this.client = client
        this.storage = storage

        if (initialized) {
            Log.d(TAG, "InboxService already initialized, updating client only")
            return
        }
        initialized = true

        // Register lifecycle observer — must be on main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            } catch (e: Exception) {
                Log.w(TAG, "Could not register lifecycle observer: ${e.message}")
            }
        }

        // Restore cached state
        restoreCachedState()

        Log.d(TAG, "InboxService initialized")
    }

    override fun onResume(owner: LifecycleOwner) {
        if (initialized) {
            val stale = _state.value.isStale
            Log.d(TAG, "App resumed, isStale=$stale, refreshing inbox if stale")
            scope.launch { refreshIfStale() }
        }
    }

    /**
     * Fetch first page of messages + unread count in parallel.
     */
    suspend fun refresh() {
        if (!initialized) return
        val c = client ?: return

        _state.value = _state.value.copy(isLoading = true)

        try {
            val (messagesResult, unreadCount) = coroutineScope {
                val messagesDeferred = async(Dispatchers.IO) { c.inboxFetchMessages(limit = 20) }
                val countDeferred = async(Dispatchers.IO) { c.inboxFetchUnreadCount() }
                messagesDeferred.await() to countDeferred.await()
            }

            @Suppress("UNCHECKED_CAST")
            val messagesList = (messagesResult["messages"] as? List<Map<String, Any?>>)
                ?.map { InboxMessage.fromMap(it) } ?: emptyList()
            val nextCursor = messagesResult["nextCursor"] as? String

            _state.value = _state.value.copy(
                messages = messagesList,
                unreadCount = unreadCount,
                nextCursor = nextCursor,
                isLoading = false,
                hasMore = nextCursor != null,
                lastFetchTime = System.currentTimeMillis()
            )

            persistState()
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false)
            Log.e(TAG, "Error refreshing inbox: ${e.message}")
        }
    }

    /**
     * Fetch next page using nextCursor and append to list.
     */
    suspend fun loadMore() {
        stateMutex.withLock {
            val currentState = _state.value
            if (!initialized || currentState.isLoading || !currentState.hasMore) return
            val c = client ?: return

            _state.value = currentState.copy(isLoading = true)

            try {
                val result = withContext(Dispatchers.IO) {
                    c.inboxFetchMessages(limit = 20, cursor = currentState.nextCursor)
                }

                @Suppress("UNCHECKED_CAST")
                val newMessages = (result["messages"] as? List<Map<String, Any?>>)
                    ?.map { InboxMessage.fromMap(it) } ?: emptyList()
                val nextCursor = result["nextCursor"] as? String

                _state.value = _state.value.copy(
                    messages = _state.value.messages + newMessages,
                    nextCursor = nextCursor,
                    isLoading = false,
                    hasMore = nextCursor != null
                )

                persistState()
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false)
                Log.e(TAG, "Error loading more inbox messages: ${e.message}")
            }
        }
    }

    /**
     * Mark single message as read (optimistic update).
     */
    fun markAsRead(messageId: String) {
        if (!initialized) return

        scope.launch {
            stateMutex.withLock {
                val currentState = _state.value
                val index = currentState.messages.indexOfFirst { it.id == messageId }
                if (index == -1) return@withLock

                val message = currentState.messages[index]
                if (message.read) return@withLock

                val originalCount = currentState.unreadCount

                // Optimistic update — emit a new list so StateFlow propagates the read-state change
                val updatedMessages = currentState.messages.toMutableList()
                updatedMessages[index] = message.copy(read = true)
                _state.value = currentState.copy(
                    messages = updatedMessages,
                    unreadCount = (currentState.unreadCount - 1).coerceAtLeast(0)
                )

                try {
                    client!!.inboxMarkAsRead(messageId)
                    persistState()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        messages = currentState.messages,
                        unreadCount = originalCount
                    )
                    Log.e(TAG, "Error marking message as read: ${e.message}")
                }
            }
        }
    }

    /**
     * Mark all messages as read (optimistic update).
     */
    fun markAllAsRead() {
        if (!initialized) return

        scope.launch {
            stateMutex.withLock {
                val currentState = _state.value
                val originalCount = currentState.unreadCount

                // Optimistic update — emit a new list so StateFlow propagates the read-state change
                val updatedMessages = currentState.messages.map { it.copy(read = true) }
                _state.value = currentState.copy(messages = updatedMessages, unreadCount = 0)

                try {
                    client!!.inboxMarkAllAsRead()
                    persistState()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        messages = currentState.messages,
                        unreadCount = originalCount
                    )
                    Log.e(TAG, "Error marking all messages as read: ${e.message}")
                }
            }
        }
    }

    /**
     * Delete a message (optimistic update).
     * Uses the service's own scope so the API call survives fragment lifecycle cancellation.
     */
    fun deleteMessage(messageId: String) {
        if (!initialized) return

        scope.launch {
            stateMutex.withLock {
                val currentState = _state.value
                val index = currentState.messages.indexOfFirst { it.id == messageId }
                if (index == -1) return@withLock

                val removedMessage = currentState.messages[index]
                val originalCount = currentState.unreadCount

                // Optimistic update
                val updatedMessages = currentState.messages.toMutableList().apply { removeAt(index) }
                val newCount = if (removedMessage.read) currentState.unreadCount
                else (currentState.unreadCount - 1).coerceAtLeast(0)

                _state.value = currentState.copy(
                    messages = updatedMessages,
                    unreadCount = newCount
                )

                try {
                    client!!.inboxDeleteMessage(messageId)
                    persistState()
                    Log.d(TAG, "Message $messageId deleted successfully")
                } catch (e: Exception) {
                    // Revert on failure
                    _state.value = _state.value.copy(
                        messages = currentState.messages,
                        unreadCount = originalCount
                    )
                    Log.e(TAG, "Error deleting message $messageId, reverting optimistic update: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Track message action (tap/click). Fire-and-forget.
     */
    fun trackAction(messageId: String) {
        if (!initialized) return
        scope.launch {
            try {
                client!!.inboxTrackAction(messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking message action: ${e.message}")
            }
        }
    }

    /**
     * Look up an inbox message by its [inboxMessageId].
     * Only refreshes if the cache is stale (> 5 min).
     */
    suspend fun getMessageById(inboxMessageId: Int): InboxMessage? {
        if (!initialized) return null
        refreshIfStale()
        return _state.value.messages.firstOrNull { it.inboxMessageId == inboxMessageId }
    }

    /**
     * Handle silent push sync signal — re-fetch first page + unread count.
     */
    suspend fun handleSyncSignal() {
        if (!initialized) return
        Log.d(TAG, "Handling sync signal")
        refresh()
    }

    /**
     * Refresh if cache is stale (> 5 min).
     */
    suspend fun refreshIfStale() {
        if (_state.value.isStale) {
            refresh()
        }
    }

    // -- Private helpers --

    private fun persistState() {
        val st = storage ?: return
        val currentState = _state.value

        val messagesJson = JSONArray().apply {
            for (msg in currentState.messages) {
                put(JSONObject(msg.toMap()))
            }
        }.toString()

        st.setString(KEY_INBOX_MESSAGES, messagesJson)
        st.setInt(KEY_INBOX_UNREAD_COUNT, currentState.unreadCount)
        if (currentState.nextCursor != null) {
            st.setString(KEY_INBOX_NEXT_CURSOR, currentState.nextCursor)
        } else {
            st.remove(KEY_INBOX_NEXT_CURSOR)
        }
        currentState.lastFetchTime?.let { st.setLong(KEY_INBOX_LAST_FETCH, it) }
    }

    private fun restoreCachedState() {
        val st = storage ?: return
        try {
            val messagesJson = st.getString(KEY_INBOX_MESSAGES) ?: return
            val jsonArray = JSONArray(messagesJson)
            val messages = mutableListOf<InboxMessage>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                messages.add(InboxMessage.fromMap(jsonObjectToMap(obj)))
            }

            val unreadCount = st.getInt(KEY_INBOX_UNREAD_COUNT) ?: 0
            val nextCursor = st.getString(KEY_INBOX_NEXT_CURSOR)
            val lastFetchTs = st.getLong(KEY_INBOX_LAST_FETCH)

            _state.value = InboxState(
                messages = messages,
                unreadCount = unreadCount,
                nextCursor = nextCursor,
                hasMore = nextCursor != null,
                lastFetchTime = lastFetchTs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring inbox cache: ${e.message}")
        }
    }

    private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(jsonArray: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.get(i)
            list.add(when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                JSONObject.NULL -> null
                else -> value
            })
        }
        return list
    }

    companion object {
        private const val TAG = "InboxService"
        private const val KEY_INBOX_MESSAGES = "inbox_messages"
        private const val KEY_INBOX_UNREAD_COUNT = "inbox_unread_count"
        private const val KEY_INBOX_NEXT_CURSOR = "inbox_next_cursor"
        private const val KEY_INBOX_LAST_FETCH = "inbox_last_fetch"

        @Volatile
        private var _instance: InboxService? = null

        val instance: InboxService
            get() = _instance ?: synchronized(this) {
                _instance ?: InboxService().also { _instance = it }
            }
    }
}
