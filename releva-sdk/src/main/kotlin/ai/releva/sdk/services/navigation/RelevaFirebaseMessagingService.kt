package ai.releva.sdk.services.navigation

import ai.releva.sdk.services.inbox.InboxService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Base FirebaseMessagingService that handles Releva push notifications
 * Apps should extend this class and provide the main activity class and notification icon
 */
abstract class RelevaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "RelevaFCMService"
        private const val DEFAULT_CHANNEL_ID = "default_channel"
        private const val SILENT_CHANNEL_ID = "silent_channel"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Get the main activity class for the app
     * This is where the app will be opened when notification is tapped
     */
    abstract fun getMainActivityClass(): Class<*>

    /**
     * Get the notification icon resource ID
     */
    abstract fun getNotificationIcon(): Int

    /**
     * Get the default notification channel ID
     * Override to use a custom channel
     */
    open fun getNotificationChannelId(): String = DEFAULT_CHANNEL_ID

    /**
     * Called when a new FCM token is generated
     * Override to handle token registration with your backend
     */
    abstract fun onPushTokenGenerated(token: String)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token generated")
        onPushTokenGenerated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Push notification received from: ${message.from}")

        // Handle inbox sync signal — can be present on any notification
        if (message.data["inbox_sync"] == "true") {
            Log.d(TAG, "Inbox sync signal received, refreshing inbox")
            scope.launch {
                try {
                    InboxService.instance.handleSyncSignal()
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling inbox sync signal", e)
                }
            }
            // Don't return — the notification may also need to be displayed
        }

        // Handle push notification and display it
        message.notification?.let { notification ->
            showNotification(notification.title, notification.body, message.data)
        } ?: run {
            // If no notification payload, create one from data payload
            val title = message.data["title"] ?: getDefaultNotificationTitle()
            val body = message.data["body"] ?: message.data["message"] ?: "You have a new notification"
            showNotification(title, body, message.data)
        }
    }

    /**
     * Get default notification title
     * Override to customize
     */
    open fun getDefaultNotificationTitle(): String = "App Notification"

    private fun showNotification(title: String?, body: String?, data: Map<String, String>) {
        createNotificationChannels()

        // Generate unique notification ID
        val notificationId = System.currentTimeMillis().toInt()

        // Create intent for notification tap
        val target = data["target"]

        // Always route through the trampoline activity so that:
        // 1. Callback URL is tracked immediately before any navigation
        // 2. No ACTION_MAIN + CATEGORY_LAUNCHER flags that cause Android to
        //    skip onNewIntent() on warm start with singleTask activities
        //
        // The callback URL is encoded in the Intent's data URI rather than extras
        // because some OEMs (notably MIUI) can lose PendingIntent extras between
        // creation and delivery. The data URI is part of the Intent's core structure
        // and survives PendingIntent serialization reliably.
        val callbackUrl = data["callbackUrl"]
        val intentUri = buildString {
            append("releva://notification")
            append("?nid=").append(notificationId)
            append("&act=").append(getMainActivityClass().name)
            if (!target.isNullOrEmpty()) append("&target=").append(android.net.Uri.encode(target))
            if (!callbackUrl.isNullOrEmpty()) append("&cb=").append(android.net.Uri.encode(callbackUrl))
            data["navigate_to_url"]?.let { append("&nav_url=").append(android.net.Uri.encode(it)) }
            data["navigate_to_screen"]?.let { append("&nav_screen=").append(android.net.Uri.encode(it)) }
            data["navigate_to_parameters"]?.let { append("&nav_params=").append(android.net.Uri.encode(it)) }
        }
        val intent = android.content.Intent(this, NotificationTrampolineActivity::class.java).apply {
            setData(android.net.Uri.parse(intentUri))
        }
        // Create pending intent with proper flags
        // NOTE: FLAG_IMMUTABLE is required for implicit intents (like ACTION_VIEW for URLs) on Android 12+
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            pendingIntentFlags
        )

        // Build the notification
        val notificationBuilder = NotificationCompat.Builder(this, getNotificationChannelId())
            .setSmallIcon(getNotificationIcon())
            .setContentTitle(title ?: getDefaultNotificationTitle())
            .setContentText(body ?: "You have a new notification")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // Dismiss when tapped
            .setContentIntent(pendingIntent)

        // Load and display image if provided
        val imageUrl = data["imageUrl"] ?: data["image"]
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val bitmap = loadImageFromUrl(imageUrl)
                if (bitmap != null) {
                    notificationBuilder
                        .setLargeIcon(bitmap)
                        .setStyle(NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as android.graphics.Bitmap?))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notification image", e)
            }
        }

        // Add action button if provided
        val buttonText = data["button"]
        if (!buttonText.isNullOrEmpty()) {
            // Create trampoline activity intent for action button
            // Use data URI (same as content tap) to avoid MIUI PendingIntent extras loss
            val buttonIntent = android.content.Intent(this, NotificationTrampolineActivity::class.java).apply {
                setData(android.net.Uri.parse(intentUri))
            }

            val buttonPendingIntent = PendingIntent.getActivity(
                this,
                notificationId + 1, // Different request code to avoid overwriting
                buttonIntent,
                pendingIntentFlags
            )

            notificationBuilder.addAction(
                0, // No icon for the action
                buttonText,
                buttonPendingIntent // Use trampoline activity to dismiss notification first
            )
        }

        // Show the notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Load image from URL for notification, capping dimensions at 1024px to prevent OOM.
     */
    private fun loadImageFromUrl(imageUrl: String): android.graphics.Bitmap? {
        return try {
            val url = java.net.URL(imageUrl)

            // First pass: determine image dimensions without allocating the full bitmap
            val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            (url.openConnection() as java.net.HttpURLConnection).also { conn ->
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()
                android.graphics.BitmapFactory.decodeStream(conn.inputStream, null, boundsOptions)
                conn.disconnect()
            }

            // Calculate inSampleSize to cap the longest side at 1024px
            val maxDim = 1024
            var sampleSize = 1
            val origMax = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            while (origMax / (sampleSize * 2) >= maxDim) sampleSize *= 2

            // Second pass: decode at reduced size
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            (url.openConnection() as java.net.HttpURLConnection).also { conn ->
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doInput = true
                conn.connect()
                val bitmap = android.graphics.BitmapFactory.decodeStream(conn.inputStream, null, decodeOptions)
                conn.disconnect()
                return bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image from URL: $imageUrl", e)
            null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create default notification channel
            val defaultChannel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Push Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications from the app"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(defaultChannel)

            // Create silent notification channel
            val silentChannel = NotificationChannel(
                SILENT_CHANNEL_ID,
                "Silent Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent notifications from the app"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(silentChannel)
        }
    }
}
