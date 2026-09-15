package ai.releva.sdk.services.navigation

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelevaFirebaseMessagingServiceTest {

    class TestFcmService : RelevaFirebaseMessagingService() {
        override fun getMainActivityClass(): Class<*> = NotificationTrampolineActivity::class.java
        override fun getNotificationIcon(): Int = android.R.drawable.ic_dialog_info
        override fun onPushTokenGenerated(token: String) = Unit
        override fun getDefaultNotificationTitle(): String = "Shopping App"
    }

    private lateinit var service: TestFcmService
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        service = Robolectric.setupService(TestFcmService::class.java)
        notificationManager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * [RemoteMessage] can only be built from the wire bundle. `gcm.n.e` is what makes FCM
     * parse a notification payload out of it; every other key stays in the data map.
     */
    private fun remoteMessage(
        data: Map<String, String> = emptyMap(),
        notificationTitle: String? = null,
        notificationBody: String? = null
    ): RemoteMessage = RemoteMessage(Bundle().apply {
        data.forEach { (key, value) -> putString(key, value) }
        if (notificationTitle != null || notificationBody != null) {
            putString("gcm.n.e", "1")
            notificationTitle?.let { putString("gcm.n.title", it) }
            notificationBody?.let { putString("gcm.n.body", it) }
        }
    })

    private fun postedNotifications(): List<Notification> = shadowOf(notificationManager).allNotifications

    @Test
    fun `a data-only sync signal posts no notification`() {
        service.onMessageReceived(remoteMessage(data = mapOf("inbox_sync" to "true")))

        assertEquals(emptyList<Notification>(), postedNotifications())
    }

    @Test
    fun `a data message with a title and a body is posted`() {
        service.onMessageReceived(
            remoteMessage(data = mapOf("title" to "Sale", "body" to "50% off", "inbox_sync" to "true"))
        )

        val posted = postedNotifications()
        assertEquals(1, posted.size)
        assertEquals("Sale", posted[0].extras.getString(Notification.EXTRA_TITLE))
        assertEquals("50% off", posted[0].extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a data message with only a body keeps the default title`() {
        service.onMessageReceived(remoteMessage(data = mapOf("body" to "50% off")))

        val posted = postedNotifications()
        assertEquals(1, posted.size)
        assertEquals("Shopping App", posted[0].extras.getString(Notification.EXTRA_TITLE))
        assertEquals("50% off", posted[0].extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a data message with only a title keeps the default body`() {
        service.onMessageReceived(remoteMessage(data = mapOf("title" to "Sale")))

        val posted = postedNotifications()
        assertEquals(1, posted.size)
        assertEquals("Sale", posted[0].extras.getString(Notification.EXTRA_TITLE))
        assertEquals("You have a new notification", posted[0].extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `a notification payload is posted`() {
        service.onMessageReceived(
            remoteMessage(notificationTitle = "Sale", notificationBody = "50% off")
        )

        val posted = postedNotifications()
        assertEquals(1, posted.size)
        assertEquals("Sale", posted[0].extras.getString(Notification.EXTRA_TITLE))
        assertEquals("50% off", posted[0].extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `the silent channel is no longer created`() {
        service.onMessageReceived(remoteMessage(data = mapOf("title" to "Sale")))

        assertEquals(
            listOf("default_channel"),
            shadowOf(notificationManager).notificationChannels.map { (it as android.app.NotificationChannel).id }
        )
    }
}
