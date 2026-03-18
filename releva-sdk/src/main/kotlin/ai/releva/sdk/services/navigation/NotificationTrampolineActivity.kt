package ai.releva.sdk.services.navigation

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline activity that dismisses notification and performs navigation.
 *
 * All notification data is encoded in the Intent's data URI (releva://notification?...)
 * rather than extras, because some OEMs (notably MIUI) lose PendingIntent extras
 * between creation and delivery.
 */
class NotificationTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "NotificationTrampoline"
        // Keep constants for backward compatibility / screen-navigation intents
        const val EXTRA_TARGET = "target"
        const val EXTRA_NAVIGATE_TO_SCREEN = "navigate_to_screen"
        const val EXTRA_NAVIGATE_TO_PARAMETERS = "navigate_to_parameters"
        const val EXTRA_NAVIGATE_TO_INBOX = "navigate_to_inbox"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        Log.d(TAG, "Trampoline activity started, uri=$uri")

        if (uri == null) {
            Log.d(TAG, "No data URI — nothing to do")
            finish()
            return
        }

        // Read all params from the data URI
        val notificationId = uri.getQueryParameter("nid")?.toIntOrNull() ?: -1
        val activityClassName = uri.getQueryParameter("act")
        val target = uri.getQueryParameter("target")
        val callbackUrl = uri.getQueryParameter("cb")
        val navigateToUrl = uri.getQueryParameter("nav_url")
        val navigateToScreen = uri.getQueryParameter("nav_screen")
        val navigateToParameters = uri.getQueryParameter("nav_params")

        Log.d(TAG, "nid=$notificationId target=$target callbackUrl=$callbackUrl")

        // Dismiss notification
        if (notificationId != -1) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notificationId)
            Log.d(TAG, "Notification dismissed: $notificationId")
        }

        // Track callback URL (notification was tapped)
        if (!callbackUrl.isNullOrEmpty()) {
            trackNotificationClick(callbackUrl)
        }

        // Perform navigation
        when (target) {
            "url" -> {
                Log.d(TAG, "Opening URL: $navigateToUrl")
                if (!navigateToUrl.isNullOrEmpty()) {
                    try {
                        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(navigateToUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        startActivity(urlIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening URL: $navigateToUrl", e)
                    }
                }
            }
            "inbox" -> {
                Log.d(TAG, "Opening inbox in activity: $activityClassName")
                if (!activityClassName.isNullOrEmpty()) {
                    try {
                        val activityClass = Class.forName(activityClassName)
                        val appIntent = Intent(this, activityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(EXTRA_TARGET, "inbox")
                            putExtra(EXTRA_NAVIGATE_TO_INBOX, true)
                        }
                        startActivity(appIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app for inbox navigation", e)
                    }
                }
            }
            "screen" -> {
                Log.d(TAG, "Opening screen: $navigateToScreen in activity: $activityClassName")
                if (!activityClassName.isNullOrEmpty()) {
                    try {
                        val activityClass = Class.forName(activityClassName)
                        val appIntent = Intent(this, activityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(EXTRA_TARGET, "screen")
                            putExtra(EXTRA_NAVIGATE_TO_SCREEN, navigateToScreen)
                            putExtra(EXTRA_NAVIGATE_TO_PARAMETERS, navigateToParameters)
                        }
                        startActivity(appIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app for screen navigation", e)
                    }
                }
            }
            else -> {
                Log.d(TAG, "Opening default app: $activityClassName")
                if (!activityClassName.isNullOrEmpty()) {
                    try {
                        val activityClass = Class.forName(activityClassName)
                        val appIntent = Intent(this, activityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(appIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app", e)
                    }
                }
            }
        }

        finish()
    }

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
