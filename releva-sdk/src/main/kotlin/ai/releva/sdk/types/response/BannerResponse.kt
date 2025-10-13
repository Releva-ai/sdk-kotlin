package ai.releva.sdk.types.response

/**
 * Banner response from Releva API
 */
data class BannerResponse(
    val token: String,
    val content: String? = null,
    val imageUrl: String? = null,
    val targetUrl: String? = null,
    val tags: List<String>? = null,
    val meta: Map<String, Any?>? = null
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): BannerResponse {
            return BannerResponse(
                token = map["token"] as? String ?: "",
                content = map["content"] as? String,
                imageUrl = map["imageUrl"] as? String,
                targetUrl = map["targetUrl"] as? String,
                tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String },
                meta = map["meta"] as? Map<String, Any?>
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "token" to token,
        "content" to content,
        "imageUrl" to imageUrl,
        "targetUrl" to targetUrl,
        "tags" to tags,
        "meta" to meta
    )
}
