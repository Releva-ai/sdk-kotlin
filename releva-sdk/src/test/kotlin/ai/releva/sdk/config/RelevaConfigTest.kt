package ai.releva.sdk.config

import org.junit.Assert.*
import org.junit.Test

class RelevaConfigTest {

    @Test
    fun `full config enables all features`() {
        val config = RelevaConfig.full()

        assertTrue(config.enableTracking)
        assertTrue(config.enableScreenTracking)
        assertTrue(config.enableInAppMessaging)
        assertTrue(config.enablePushNotifications)
        assertTrue(config.enableAnalytics)
    }

    @Test
    fun `tracking only config`() {
        val config = RelevaConfig.trackingOnly()

        assertTrue(config.enableTracking)
        assertTrue(config.enableScreenTracking)
        assertFalse(config.enableInAppMessaging)
        assertFalse(config.enablePushNotifications)
        assertTrue(config.enableAnalytics)
    }

    @Test
    fun `messaging only config`() {
        val config = RelevaConfig.messagingOnly()

        assertFalse(config.enableTracking)
        assertFalse(config.enableScreenTracking)
        assertTrue(config.enableInAppMessaging)
        assertFalse(config.enablePushNotifications)
        assertFalse(config.enableAnalytics)
    }

    @Test
    fun `push only config`() {
        val config = RelevaConfig.pushOnly()

        assertFalse(config.enableTracking)
        assertFalse(config.enableScreenTracking)
        assertFalse(config.enableInAppMessaging)
        assertTrue(config.enablePushNotifications)
        assertFalse(config.enableAnalytics)
    }

    @Test
    fun `custom config with specific features`() {
        val config = RelevaConfig(
            enableTracking = true,
            enableScreenTracking = false,
            enableInAppMessaging = true,
            enablePushNotifications = false,
            enableAnalytics = true
        )

        assertTrue(config.enableTracking)
        assertFalse(config.enableScreenTracking)
        assertTrue(config.enableInAppMessaging)
        assertFalse(config.enablePushNotifications)
        assertTrue(config.enableAnalytics)
    }

    @Test
    fun `custom config with all features disabled`() {
        val config = RelevaConfig(
            enableTracking = false,
            enableScreenTracking = false,
            enableInAppMessaging = false,
            enablePushNotifications = false,
            enableAnalytics = false
        )

        assertFalse(config.enableTracking)
        assertFalse(config.enableScreenTracking)
        assertFalse(config.enableInAppMessaging)
        assertFalse(config.enablePushNotifications)
        assertFalse(config.enableAnalytics)
    }
}
