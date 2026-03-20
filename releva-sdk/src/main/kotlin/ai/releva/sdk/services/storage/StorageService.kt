package ai.releva.sdk.services.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Storage service for persisting SDK data using SharedPreferences
 */
class StorageService private constructor(context: Context) {

    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "releva_sdk_prefs"

        // Storage keys
        private const val KEY_PROFILE_ID = "rprofile_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_TIMESTAMP = "session_timestamp"
        private const val KEY_CART_DATA = "cart_data"
        private const val KEY_WISHLIST_DATA = "wishlist_data"
        private const val KEY_PENDING_MESSAGE_EVENTS = "pending_message_events"
        private const val KEY_PENDING_ENGAGEMENT_EVENTS = "pending_engagement_events"
        private const val KEY_LAST_MESSAGE_FETCH = "last_message_fetch"
        private const val KEY_DEVICE_SESSION_COUNT = "device_session_count"
        private const val KEY_DEVICE_FIRST_SEEN = "device_first_seen"
        private const val KEY_DEVICE_LAST_SESSION_TS = "device_last_session_ts"
        private const val KEY_DEVICE_VIEWS = "device_views"

        @Volatile
        private var instance: StorageService? = null

        fun getInstance(context: Context): StorageService {
            return instance ?: synchronized(this) {
                instance ?: StorageService(context.applicationContext).also { instance = it }
            }
        }
    }

    // Profile and Device Management
    fun setProfileId(profileId: String) {
        preferences.edit().putString(KEY_PROFILE_ID, profileId).apply()
    }

    fun getProfileId(): String? = preferences.getString(KEY_PROFILE_ID, null)

    fun setDeviceId(deviceId: String) {
        preferences.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String? = preferences.getString(KEY_DEVICE_ID, null)

    // Session Management
    fun setSessionId(sessionId: String) {
        preferences.edit().putString(KEY_SESSION_ID, sessionId).apply()
    }

    fun getSessionId(): String? = preferences.getString(KEY_SESSION_ID, null)

    fun setSessionTimestamp(timestamp: Long) {
        preferences.edit().putLong(KEY_SESSION_TIMESTAMP, timestamp).apply()
    }

    fun getSessionTimestamp(): Long? {
        return if (preferences.contains(KEY_SESSION_TIMESTAMP)) {
            preferences.getLong(KEY_SESSION_TIMESTAMP, 0L)
        } else {
            null
        }
    }

    // Device Analytics
    fun setDeviceSessionCount(count: Int) {
        preferences.edit().putInt(KEY_DEVICE_SESSION_COUNT, count).apply()
    }

    @Synchronized
    fun getDeviceSessionCount(): Int = preferences.getInt(KEY_DEVICE_SESSION_COUNT, 0)

    fun setDeviceFirstSeenAt(iso: String) {
        preferences.edit().putString(KEY_DEVICE_FIRST_SEEN, iso).apply()
    }

    fun getDeviceFirstSeenAt(): String? = preferences.getString(KEY_DEVICE_FIRST_SEEN, null)

    fun setDeviceLastSessionTimestamp(ts: Long) {
        preferences.edit().putLong(KEY_DEVICE_LAST_SESSION_TS, ts).apply()
    }

    fun getDeviceLastSessionTimestamp(): Long? {
        return if (preferences.contains(KEY_DEVICE_LAST_SESSION_TS)) {
            preferences.getLong(KEY_DEVICE_LAST_SESSION_TS, 0L)
        } else {
            null
        }
    }

    fun setDeviceViewsCount(count: Long) {
        preferences.edit().putLong(KEY_DEVICE_VIEWS, count).apply()
    }

    @Synchronized
    fun getDeviceViewsCount(): Long = preferences.getLong(KEY_DEVICE_VIEWS, 0L)

    @Synchronized
    fun incrementDeviceSessionCount(): Int {
        val count = getDeviceSessionCount() + 1
        setDeviceSessionCount(count)
        return count
    }

    @Synchronized
    fun incrementDeviceViewsCount(): Long {
        val count = getDeviceViewsCount() + 1
        setDeviceViewsCount(count)
        return count
    }

    // Cart Management
    fun setCartData(cartJson: String) {
        preferences.edit().putString(KEY_CART_DATA, cartJson).apply()
    }

    fun getCartData(): String? = preferences.getString(KEY_CART_DATA, null)

    // Wishlist Management
    fun setWishlistData(wishlistJson: List<String>) {
        val jsonArray = JSONArray(wishlistJson)
        preferences.edit().putString(KEY_WISHLIST_DATA, jsonArray.toString()).apply()
    }

    fun getWishlistData(): List<String>? {
        val jsonString = preferences.getString(KEY_WISHLIST_DATA, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Analytics Data
    fun setPendingMessageEvents(events: List<String>) {
        val jsonArray = JSONArray(events)
        preferences.edit().putString(KEY_PENDING_MESSAGE_EVENTS, jsonArray.toString()).apply()
    }

    fun getPendingMessageEvents(): List<String>? {
        val jsonString = preferences.getString(KEY_PENDING_MESSAGE_EVENTS, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Engagement Data
    fun setPendingEngagementEvents(events: List<String>) {
        val jsonArray = JSONArray(events)
        preferences.edit().putString(KEY_PENDING_ENGAGEMENT_EVENTS, jsonArray.toString()).apply()
    }

    fun getPendingEngagementEvents(): List<String>? {
        val jsonString = preferences.getString(KEY_PENDING_ENGAGEMENT_EVENTS, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Settings Data
    fun setLastMessageFetch(timestamp: Long) {
        preferences.edit().putLong(KEY_LAST_MESSAGE_FETCH, timestamp).apply()
    }

    fun getLastMessageFetch(): Long? {
        return if (preferences.contains(KEY_LAST_MESSAGE_FETCH)) {
            preferences.getLong(KEY_LAST_MESSAGE_FETCH, 0L)
        } else {
            null
        }
    }

    // Generic methods
    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = preferences.getString(key, null)

    fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String): Int? {
        return if (preferences.contains(key)) {
            preferences.getInt(key, 0)
        } else {
            null
        }
    }

    fun setLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String): Long? {
        return if (preferences.contains(key)) {
            preferences.getLong(key, 0L)
        } else {
            null
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String): Boolean? {
        return if (preferences.contains(key)) {
            preferences.getBoolean(key, false)
        } else {
            null
        }
    }

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    fun containsKey(key: String): Boolean = preferences.contains(key)

    fun clear() {
        preferences.edit().clear().apply()
    }
}
