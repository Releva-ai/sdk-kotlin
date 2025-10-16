package ai.releva.sdk.services.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONObject

/**
 * Service for handling push notification navigation
 * Supports screen navigation and URL opening
 */
class NavigationService private constructor() {
    private var navigationHandler: NavigationHandler? = null

    companion object {
        private const val TAG = "NavigationService"

        @Volatile
        private var instance: NavigationService? = null

        fun getInstance(): NavigationService {
            return instance ?: synchronized(this) {
                instance ?: NavigationService().also { instance = it }
            }
        }
    }

    /**
     * Set the navigation handler for screen navigation
     * Apps must call this during initialization to enable screen navigation
     */
    fun setNavigationHandler(handler: NavigationHandler) {
        this.navigationHandler = handler
        Log.d(TAG, "Navigation handler set: ${handler::class.simpleName}")
    }

    /**
     * Clear the navigation handler
     * Primarily for testing purposes
     */
    fun clearNavigationHandler() {
        this.navigationHandler = null
        Log.d(TAG, "Navigation handler cleared")
    }

    /**
     * Handle notification navigation based on intent data
     * @param context Android context
     * @param intent Intent from notification tap
     * @return true if navigation was handled, false otherwise
     */
    fun handleNotificationNavigation(context: Context, intent: Intent): Boolean {
        val target = intent.getStringExtra("target")

        Log.d(TAG, "Handling notification navigation with target: $target")

        // Track callback URL (notification was tapped)
        val callbackUrl = intent.getStringExtra("callbackUrl")
        if (!callbackUrl.isNullOrEmpty()) {
            trackNotificationClick(callbackUrl)
        }

        // Check if this came from action button and dismiss notification
        val fromActionButton = intent.getBooleanExtra("from_action_button", false)
        if (fromActionButton) {
            val notificationId = intent.getIntExtra("notification_id", -1)
            if (notificationId != -1) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(notificationId)
                Log.d(TAG, "Dismissed notification from action button: $notificationId")
            }
        }

        return when (target) {
            "screen" -> handleScreenNavigation(intent)
            "url" -> handleUrlNavigation(context, intent)
            else -> {
                Log.d(TAG, "No specific navigation target")
                false
            }
        }
    }

    /**
     * Handle screen navigation
     */
    private fun handleScreenNavigation(intent: Intent): Boolean {
        val handler = navigationHandler
        if (handler == null) {
            Log.w(TAG, "NavigationHandler not set. Call setNavigationHandler() during app initialization.")
            return false
        }

        val screenName = intent.getStringExtra("navigate_to_screen")
        val parametersJson = intent.getStringExtra("navigate_to_parameters")

        if (screenName.isNullOrEmpty()) {
            Log.w(TAG, "No screen name provided for navigation")
            return false
        }

        // Parse parameters
        val parameters = parseParameters(parametersJson)

        try {
            handler.navigateToScreen(screenName, parameters)
            Log.d(TAG, "Successfully navigated to screen: $screenName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to screen: $screenName", e)
            return false
        }
    }

    /**
     * Handle URL navigation
     */
    private fun handleUrlNavigation(context: Context, intent: Intent): Boolean {
        val url = intent.getStringExtra("navigate_to_url")

        if (url.isNullOrEmpty()) {
            Log.w(TAG, "No URL provided for navigation")
            return false
        }

        try {
            val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(urlIntent)
            Log.d(TAG, "Successfully opened URL: $url")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL: $url", e)
            return false
        }
    }

    /**
     * Parse JSON parameters into Bundle
     */
    private fun parseParameters(parametersJson: String?): Bundle {
        if (parametersJson.isNullOrEmpty()) {
            return Bundle()
        }

        return try {
            val jsonObj = JSONObject(parametersJson)
            Bundle().apply {
                jsonObj.keys().forEach { key ->
                    when (val value = jsonObj.get(key)) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Double -> putDouble(key, value)
                        is Boolean -> putBoolean(key, value)
                        else -> putString(key, value.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing navigation parameters: $parametersJson", e)
            Bundle()
        }
    }

    /**
     * Create notification intent based on target
     * This should be called by the FirebaseMessagingService
     */
    fun createNotificationIntent(
        context: Context,
        activityClass: Class<*>,
        data: Map<String, String>
    ): Intent {
        val target = data["target"]

        return when (target) {
            "url" -> createUrlIntent(data)
            else -> createAppIntent(context, activityClass, data)
        }
    }

    /**
     * Create intent to open URL
     */
    private fun createUrlIntent(data: Map<String, String>): Intent {
        val url = data["navigate_to_url"]

        Log.d(TAG, "Creating URL intent for: $url")

        return if (!url.isNullOrEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                addCategory(Intent.CATEGORY_BROWSABLE)
                Log.d(TAG, "URL intent created with flags: $flags, categories: $categories")
            }
        } else {
            throw IllegalArgumentException("URL target requires navigate_to_url field")
        }
    }

    /**
     * Create intent to open app
     */
    private fun createAppIntent(
        context: Context,
        activityClass: Class<*>,
        data: Map<String, String>
    ): Intent {
        return Intent(context, activityClass).apply {
            // Use single top to avoid BAL issues
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)

            // Add all notification data
            data.forEach { (key, value) ->
                putExtra(key, value)
            }
        }
    }

    /**
     * Track notification click by calling the callback URL in background
     */
    private fun trackNotificationClick(callbackUrl: String) {
        Log.d(TAG, "Tracking notification click: $callbackUrl")

        // Launch thread in background to make HTTP request
        Thread {
            try {
                val url = java.net.URL(callbackUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Successfully tracked notification click: $responseCode")
                } else {
                    Log.w(TAG, "Failed to track notification click: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking notification click", e)
            }
        }.start()
    }
}
