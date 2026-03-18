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

        // Shared client — expensive to create (thread pool + connection pool)
        private val httpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
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
        val target = intent.getStringExtra("target") ?: return false

        Log.d(TAG, "Handling notification navigation with target: $target")

        // Extract all notification data before clearing the intent
        val callbackUrl = intent.getStringExtra("callbackUrl")
        val fromActionButton = intent.getBooleanExtra("from_action_button", false)
        val notificationId = intent.getIntExtra("notification_id", -1)
        val screenName = intent.getStringExtra("navigate_to_screen")
        val parametersJson = intent.getStringExtra("navigate_to_parameters")
        val navigateToUrl = intent.getStringExtra("navigate_to_url")

        // Clear notification extras immediately so they aren't re-processed
        // on activity recreation (rotation, back+forward, etc.)
        clearNotificationExtras(intent)

        // Track callback URL (notification was tapped)
        if (!callbackUrl.isNullOrEmpty()) {
            trackNotificationClick(callbackUrl)
        }

        // Check if this came from action button and dismiss notification
        if (fromActionButton && notificationId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Dismissed notification from action button: $notificationId")
        }

        return when (target) {
            "screen" -> handleScreenNavigation(screenName, parametersJson)
            "url" -> handleUrlNavigation(context, navigateToUrl)
            else -> {
                Log.d(TAG, "No specific navigation target")
                false
            }
        }
    }

    /**
     * Handle screen navigation
     */
    private fun handleScreenNavigation(screenName: String?, parametersJson: String?): Boolean {
        val handler = navigationHandler
        if (handler == null) {
            Log.w(TAG, "NavigationHandler not set. Call setNavigationHandler() during app initialization.")
            return false
        }

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
    private fun handleUrlNavigation(context: Context, url: String?): Boolean {
        if (url.isNullOrEmpty()) {
            Log.w(TAG, "No URL provided for navigation")
            return false
        }

        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.w(TAG, "Rejecting non-http(s) URL from push notification: $url")
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
     * Clear notification-related extras from the intent to prevent
     * re-processing on activity recreation (rotation, back navigation, etc.)
     */
    private fun clearNotificationExtras(intent: Intent) {
        intent.removeExtra("target")
        intent.removeExtra("callbackUrl")
        intent.removeExtra("from_action_button")
        intent.removeExtra("notification_id")
        intent.removeExtra("navigate_to_screen")
        intent.removeExtra("navigate_to_parameters")
        intent.removeExtra("navigate_to_url")
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
            // Use single top so onNewIntent() is called when the activity already exists.
            // Do NOT set ACTION_MAIN + CATEGORY_LAUNCHER — that combination causes Android
            // to just bring the task to foreground without delivering the intent (and its
            // extras) on warm start with singleTask activities.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

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

        Thread {
            try {
                val request = okhttp3.Request.Builder()
                    .url(callbackUrl)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                Log.d(TAG, "Track click response: ${response.code} (redirected=${response.priorResponse != null})")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking notification click", e)
            }
        }.start()
    }
}
