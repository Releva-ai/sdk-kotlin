package ai.releva.sdk.services.navigation

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * BroadcastReceiver that handles notification action button clicks
 * Dismisses the notification and then performs the navigation
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationActionRcvr"
        const val ACTION_NOTIFICATION_BUTTON_CLICKED = "ai.releva.sdk.ACTION_NOTIFICATION_BUTTON_CLICKED"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TARGET = "target"
        const val EXTRA_NAVIGATE_TO_URL = "navigate_to_url"
        const val EXTRA_NAVIGATE_TO_SCREEN = "navigate_to_screen"
        const val EXTRA_NAVIGATE_TO_PARAMETERS = "navigate_to_parameters"
        const val EXTRA_ACTIVITY_CLASS = "activity_class"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Notification action button clicked")

        // Use goAsync() to extend processing time and maintain wake lock
        val pendingResult = goAsync()

        try {
            // Get notification ID and dismiss the notification FIRST
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            if (notificationId != -1) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
                Log.d(TAG, "Notification dismissed: $notificationId")
            }

            // Small delay to ensure notification is fully dismissed
            Thread.sleep(50)

            // Get navigation target
            val target = intent.getStringExtra(EXTRA_TARGET)
            Log.d(TAG, "Navigation target: $target")

            when (target) {
                "url" -> {
                    // Open URL
                    val url = intent.getStringExtra(EXTRA_NAVIGATE_TO_URL)
                    Log.d(TAG, "Opening URL: $url")
                    if (!url.isNullOrEmpty()) {
                        try {
                            val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            }
                            context.startActivity(urlIntent)
                            Log.d(TAG, "Successfully opened URL: $url")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error opening URL: $url", e)
                        }
                    }
                }
                "screen" -> {
                    // Navigate to screen
                    val screenName = intent.getStringExtra(EXTRA_NAVIGATE_TO_SCREEN)
                    val parameters = intent.getStringExtra(EXTRA_NAVIGATE_TO_PARAMETERS)
                    val activityClassName = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)

                    Log.d(TAG, "Opening screen: $screenName in activity: $activityClassName")

                    if (!activityClassName.isNullOrEmpty()) {
                        try {
                            val activityClass = Class.forName(activityClassName)
                            val appIntent = Intent(context, activityClass).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                action = Intent.ACTION_MAIN
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                putExtra(EXTRA_TARGET, "screen")
                                putExtra(EXTRA_NAVIGATE_TO_SCREEN, screenName)
                                putExtra(EXTRA_NAVIGATE_TO_PARAMETERS, parameters)
                            }
                            context.startActivity(appIntent)
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
                            val appIntent = Intent(context, activityClass).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                action = Intent.ACTION_MAIN
                                addCategory(Intent.CATEGORY_LAUNCHER)
                            }
                            Log.d(TAG, "Starting activity with intent - Action: ${appIntent.action}, Flags: ${appIntent.flags}")
                            context.startActivity(appIntent)
                            Log.d(TAG, "Successfully opened app")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error opening app", e)
                        }
                    } else {
                        Log.e(TAG, "Activity class name is null or empty")
                    }
                }
            }
        } finally {
            // Signal that the broadcast is complete
            pendingResult.finish()
        }
    }
}
