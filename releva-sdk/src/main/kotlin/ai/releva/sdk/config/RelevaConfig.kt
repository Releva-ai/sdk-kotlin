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
    val enableInbox: Boolean = true,
    // RelevaClient logs every request/response through its central execute() path (method,
    // path, status, timing, and — for failures — up to 500 chars of the response body).
    // Defaults on since it has shipped that way; an integrator who cannot have SDK output in
    // their release logcat needs a way to turn it off entirely.
    val enableRequestLogging: Boolean = true,
    // Total attempts a request gets before its failure reaches the caller, not retries on top
    // of the first try: 3 is one call plus two retries, and 0 or 1 is a single call with no
    // retry. Named and defaulted to match the Swift SDK's RelevaConfig.maxRetryAttempts so the
    // two recover from the same blip.
    val maxRetryAttempts: Int = 3
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
        "enableInbox" to enableInbox,
        "enableRequestLogging" to enableRequestLogging,
        "maxRetryAttempts" to maxRetryAttempts
    )
}
