package ai.releva.sdk.types.response

/**
 * Banner response from Releva API
 */
data class BannerResponse(
    val token: String,
    val bannerId: Int = 0,
    val segmentId: Int = 0,
    val name: String = "",
    val html: String = "",
    val tags: List<String>? = null,
    val mergeContext: Map<String, String>? = null,
    val displayType: String? = null,
    val delaySeconds: Int? = null,
    val scrollPercentage: Int? = null,
    val cssSelector: String? = null,
    val trigger: String? = null,
    val displayStrategy: String? = null,
    val displayPosition: String? = null,
    val advancedStyling: Boolean? = null,
    val cssStyles: Map<String, Any?> = emptyMap(),
    val design: Map<String, Any?>? = null,
    // Legacy fields kept for backwards compatibility
    val content: String? = null,
    val imageUrl: String? = null,
    val targetUrl: String? = null,
    val meta: Map<String, Any?>? = null
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BannerResponse {
            return BannerResponse(
                token = map["token"] as? String ?: "",
                bannerId = (map["bannerId"] as? Number)?.toInt() ?: 0,
                segmentId = (map["segmentId"] as? Number)?.toInt() ?: 0,
                name = map["name"] as? String ?: "",
                html = map["html"] as? String ?: "",
                tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String },
                mergeContext = (map["mergeContext"] as? Map<*, *>)?.let { m ->
                    m.entries.associate { (k, v) -> k.toString() to v.toString() }
                },
                displayType = map["displayType"] as? String,
                delaySeconds = (map["delaySeconds"] as? Number)?.toInt(),
                scrollPercentage = (map["scrollPercentage"] as? Number)?.toInt(),
                cssSelector = map["cssSelector"] as? String,
                trigger = map["trigger"] as? String,
                displayStrategy = map["displayStrategy"] as? String ?: "afterbegin",
                displayPosition = map["displayPosition"] as? String,
                advancedStyling = map["advancedStyling"] as? Boolean ?: false,
                cssStyles = map["cssStyles"] as? Map<String, Any?> ?: emptyMap(),
                design = map["design"] as? Map<String, Any?>,
                content = map["content"] as? String,
                imageUrl = map["imageUrl"] as? String,
                targetUrl = map["targetUrl"] as? String,
                meta = map["meta"] as? Map<String, Any?>
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "token" to token,
        "bannerId" to bannerId,
        "segmentId" to segmentId,
        "name" to name,
        "html" to html,
        "tags" to tags,
        "mergeContext" to mergeContext,
        "displayType" to displayType,
        "delaySeconds" to delaySeconds,
        "scrollPercentage" to scrollPercentage,
        "cssSelector" to cssSelector,
        "trigger" to trigger,
        "displayStrategy" to displayStrategy,
        "displayPosition" to displayPosition,
        "advancedStyling" to advancedStyling,
        "cssStyles" to cssStyles,
        "design" to design,
        "content" to content,
        "imageUrl" to imageUrl,
        "targetUrl" to targetUrl,
        "meta" to meta
    )
}
