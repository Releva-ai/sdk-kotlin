package ai.releva.sdk.config

/**
 * Configuration class for Releva SDK
 */
data class RelevaConfig(
    val enableTracking: Boolean = true,
    val enableScreenTracking: Boolean = true,
    val enableInAppMessaging: Boolean = true,
    val enablePushNotifications: Boolean = true,
    val enableAnalytics: Boolean = true,
    val enableInbox: Boolean = true
) {
    companion object {
        fun full() = RelevaConfig(
            enableTracking = true,
            enableScreenTracking = true,
            enableInAppMessaging = true,
            enablePushNotifications = true,
            enableAnalytics = true,
            enableInbox = true
        )

        fun messagingOnly() = RelevaConfig(
            enableTracking = false,
            enableScreenTracking = false,
            enableInAppMessaging = true,
            enablePushNotifications = false,
            enableAnalytics = false,
            enableInbox = true
        )

        fun trackingOnly() = RelevaConfig(
            enableTracking = true,
            enableScreenTracking = true,
            enableInAppMessaging = false,
            enablePushNotifications = false,
            enableAnalytics = true,
            enableInbox = false
        )

        fun pushOnly() = RelevaConfig(
            enableTracking = false,
            enableScreenTracking = false,
            enableInAppMessaging = false,
            enablePushNotifications = true,
            enableAnalytics = false,
            enableInbox = false
        )
    }

    fun toMap(): Map<String, Any> = mapOf(
        "enableTracking" to enableTracking,
        "enableScreenTracking" to enableScreenTracking,
        "enableInAppMessaging" to enableInAppMessaging,
        "enablePushNotifications" to enablePushNotifications,
        "enableAnalytics" to enableAnalytics,
        "enableInbox" to enableInbox
    )
}
