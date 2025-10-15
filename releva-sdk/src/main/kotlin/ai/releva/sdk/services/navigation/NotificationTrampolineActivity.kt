package ai.releva.sdk.services.navigation

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline activity that dismisses notification and performs navigation
 * This bypasses Background Activity Launch restrictions because it's an Activity
 */
class NotificationTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "NotificationTrampoline"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TARGET = "target"
        const val EXTRA_NAVIGATE_TO_URL = "navigate_to_url"
        const val EXTRA_NAVIGATE_TO_SCREEN = "navigate_to_screen"
        const val EXTRA_NAVIGATE_TO_PARAMETERS = "navigate_to_parameters"
        const val EXTRA_ACTIVITY_CLASS = "activity_class"
        const val EXTRA_CALLBACK_URL = "callbackUrl"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Trampoline activity started")

        // Dismiss notification
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Notification dismissed: $notificationId")
        }

        // Track callback URL (notification was tapped)
        val callbackUrl = intent.getStringExtra(EXTRA_CALLBACK_URL)
        if (!callbackUrl.isNullOrEmpty()) {
            trackNotificationClick(callbackUrl)
        }

        // Get navigation target
        val target = intent.getStringExtra(EXTRA_TARGET)
        Log.d(TAG, "Navigation target: $target")

        // Perform navigation
        when (target) {
            "url" -> {
                val url = intent.getStringExtra(EXTRA_NAVIGATE_TO_URL)
                Log.d(TAG, "Opening URL: $url")
                if (!url.isNullOrEmpty()) {
                    try {
                        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        startActivity(urlIntent)
                        Log.d(TAG, "Successfully opened URL: $url")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening URL: $url", e)
                    }
                }
            }
            "screen" -> {
                val screenName = intent.getStringExtra(EXTRA_NAVIGATE_TO_SCREEN)
                val parameters = intent.getStringExtra(EXTRA_NAVIGATE_TO_PARAMETERS)
                val activityClassName = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)

                Log.d(TAG, "Opening screen: $screenName in activity: $activityClassName")

                if (!activityClassName.isNullOrEmpty()) {
                    try {
                        val activityClass = Class.forName(activityClassName)
                        val appIntent = Intent(this, activityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            putExtra(EXTRA_TARGET, "screen")
                            putExtra(EXTRA_NAVIGATE_TO_SCREEN, screenName)
                            putExtra(EXTRA_NAVIGATE_TO_PARAMETERS, parameters)
                        }
                        startActivity(appIntent)
                        Log.d(TAG, "Successfully opened app with screen: $screenName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app for screen navigation", e)
                    }
                }
            }
            else -> {
                // Default - open app
                val activityClassName = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)
                Log.d(TAG, "Opening default app: $activityClassName")

                if (!activityClassName.isNullOrEmpty()) {
                    try {
                        val activityClass = Class.forName(activityClassName)
                        val appIntent = Intent(this, activityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            action = Intent.ACTION_MAIN
                            addCategory(Intent.CATEGORY_LAUNCHER)
                        }
                        Log.d(TAG, "Starting activity")
                        startActivity(appIntent)
                        Log.d(TAG, "Successfully opened app")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app", e)
                    }
                }
            }
        }

        // Finish this trampoline activity immediately
        finish()
    }

    /**
     * Track notification click by calling the callback URL in background
     */
    private fun trackNotificationClick(callbackUrl: String) {
        Log.d(TAG, "Tracking notification click: $callbackUrl")

        // Launch coroutine in background to make HTTP request
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
