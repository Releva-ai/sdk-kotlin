package ai.releva.sdk.services.engagement

import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service for tracking push notification engagement
 */
class EngagementTrackingService(private val context: Context) {

    private val storage = StorageService.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pendingEvents = mutableListOf<String>()
    private var batchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "RelevaEngagement"
        private const val BATCH_INTERVAL_MS = 30_000L // 30 seconds
        private const val RELEVA_CLICK_ACTION = "RELEVA_NOTIFICATION_CLICK"
    }

    /**
     * Initialize the engagement tracking service
     */
    fun initialize() {
        loadPendingEvents()
        startBatchTimer()
    }

    /**
     * Load pending events from storage
     */
    private fun loadPendingEvents() {
        try {
            val events = storage.getPendingEngagementEvents()
            if (events != null) {
                pendingEvents.clear()
                pendingEvents.addAll(events)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending events", e)
        }
    }

    /**
     * Save pending events to storage
     */
    private fun savePendingEvents() {
        try {
            storage.setPendingEngagementEvents(pendingEvents.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pending events", e)
        }
    }

    /**
     * Start batch timer for sending events
     */
    private fun startBatchTimer() {
        batchJob?.cancel()
        batchJob = scope.launch {
            while (isActive) {
                delay(BATCH_INTERVAL_MS)
                sendPendingEvents()
            }
        }
    }

    /**
     * Check if a notification is from Releva
     */
    fun isRelevaMessage(data: Map<String, String>): Boolean {
        return data["click_action"] == RELEVA_CLICK_ACTION
    }

    /**
     * Track engagement event from notification data
     */
    suspend fun trackEngagement(data: Map<String, String>) = withContext(Dispatchers.IO) {
        if (!isRelevaMessage(data)) return@withContext

        val callbackUrl = data["callbackUrl"]
        if (callbackUrl != null) {
            pendingEvents.add(callbackUrl)
            savePendingEvents()
            sendPendingEvents()
        }
    }

    /**
     * Get pending events count
     */
    fun getPendingEventsCount(): Int = pendingEvents.size

    /**
     * Get pending events list
     */
    fun getPendingEvents(): List<String> = pendingEvents.toList()

    /**
     * Send pending events to server
     */
    suspend fun sendPendingEvents() = withContext(Dispatchers.IO) {
        if (pendingEvents.isEmpty()) return@withContext

        // Check connectivity
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No internet connection, keeping events pending")
            return@withContext
        }

        val eventsToSend = pendingEvents.toList()
        pendingEvents.clear()
        savePendingEvents()

        for (uri in eventsToSend) {
            try {
                Log.d(TAG, "Sending GET request to: $uri")
                val request = Request.Builder()
                    .url(uri)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                Log.d(TAG, "Received response with status: ${response.code} and body: $body")

                if (!response.isSuccessful) {
                    throw Exception("Backend API error: ${response.code} - $body")
                }
            } catch (e: Exception) {
                // Re-add event back to pending list on error
                pendingEvents.add(uri)
                savePendingEvents()
                Log.e(TAG, "Error sending event", e)
            }
        }

        Log.d(TAG, "Sent ${eventsToSend.size} events successfully")
    }

    /**
     * Force send all pending events immediately
     */
    suspend fun forceSendPendingEvents() {
        sendPendingEvents()
    }

    /**
     * Clear all pending events
     */
    fun clearPendingEvents() {
        pendingEvents.clear()
        savePendingEvents()
        Log.d(TAG, "Cleared all pending events")
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    /**
     * Dispose resources
     */
    fun dispose() {
        batchJob?.cancel()
        scope.cancel()
        Log.d(TAG, "EngagementTrackingService disposed")
    }
}
