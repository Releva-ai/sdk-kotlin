package ai.releva.sdk.config

/**
 * Configuration class for Releva SDK
 *
 * @property enableTracking Enable tracking functionality (default: true)
 * @property enableScreenTracking Enable automatic screen tracking (default: true)
 * @property enableInAppMessaging Enable in-app messaging (default: true)
 * @property enablePushNotifications Enable push notification tracking (default: true)
 * @property enableAnalytics Enable analytics (default: true)
 */
data class RelevaConfig(
    val enableTracking: Boolean = true,
    val enableScreenTracking: Boolean = true,
    val enableInAppMessaging: Boolean = true,
    val enablePushNotifications: Boolean = true,
    val enableAnalytics: Boolean = true
) {
    companion object {
        /**
         * Configuration for full functionality (default)
         */
        fun full() = RelevaConfig(
            enableTracking = true,
            enableScreenTracking = true,
            enableInAppMessaging = true,
            enablePushNotifications = true,
            enableAnalytics = true
        )

        /**
         * Configuration for tracking disabled (only in-app messaging)
         */
        fun messagingOnly() = RelevaConfig(
            enableTracking = false,
            enableScreenTracking = false,
            enableInAppMessaging = true,
            enablePushNotifications = false,
            enableAnalytics = false
        )

        /**
         * Configuration for analytics only (no messaging)
         */
        fun trackingOnly() = RelevaConfig(
            enableTracking = true,
            enableScreenTracking = true,
            enableInAppMessaging = false,
            enablePushNotifications = false,
            enableAnalytics = true
        )

        /**
         * Configuration for push notifications only
         */
        fun pushOnly() = RelevaConfig(
            enableTracking = false,
            enableScreenTracking = false,
            enableInAppMessaging = false,
            enablePushNotifications = true,
            enableAnalytics = false
        )
    }

    fun toMap(): Map<String, Any> = mapOf(
        "enableTracking" to enableTracking,
        "enableScreenTracking" to enableScreenTracking,
        "enableInAppMessaging" to enableInAppMessaging,
        "enablePushNotifications" to enablePushNotifications,
        "enableAnalytics" to enableAnalytics
    )
}
