package ai.releva.sdk.types.response

/**
 * Story slide response data
 */
data class StorySlideResponse(
    val id: Any? = null,
    val html: String? = null,
    val design: Map<String, Any?>? = null,
    val durationSeconds: Int = 5,
    val actionType: String? = null,
    val actionUrl: String? = null,
    val actionLabel: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): StorySlideResponse {
            @Suppress("UNCHECKED_CAST")
            return StorySlideResponse(
                id = map["id"],
                html = map["html"] as? String,
                design = map["design"] as? Map<String, Any?>,
                durationSeconds = (map["durationSeconds"] as? Number)?.toInt() ?: 5,
                actionType = map["actionType"] as? String,
                actionUrl = map["actionUrl"] as? String,
                actionLabel = map["actionLabel"] as? String
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "html" to html,
        "design" to design,
        "durationSeconds" to durationSeconds,
        "actionType" to actionType,
        "actionUrl" to actionUrl,
        "actionLabel" to actionLabel
    )
}

/**
 * Story response containing metadata and slides
 */
data class StoryResponse(
    val token: String,
    val storyId: Any? = null,
    val name: String? = null,
    val trigger: String? = null,
    val delaySeconds: Int? = null,
    val scrollPercentage: Int? = null,
    val endBehavior: String = "dismiss",  // dismiss, loop, stayOnLast
    val progressIndicatorColor: String = "#FFFFFF",
    val progressIndicatorInactiveColor: String = "#FFFFFF4D",
    val tags: List<String>? = null,
    val slides: List<StorySlideResponse> = emptyList(),
    val mergeContext: Map<String, String>? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): StoryResponse {
            @Suppress("UNCHECKED_CAST")
            return StoryResponse(
                token = map["token"] as? String ?: "",
                storyId = map["storyId"],
                name = map["name"] as? String,
                trigger = map["trigger"] as? String,
                delaySeconds = (map["delaySeconds"] as? Number)?.toInt(),
                scrollPercentage = (map["scrollPercentage"] as? Number)?.toInt(),
                endBehavior = map["endBehavior"] as? String ?: "dismiss",
                progressIndicatorColor = map["progressIndicatorColor"] as? String ?: "#FFFFFF",
                progressIndicatorInactiveColor = map["progressIndicatorInactiveColor"] as? String ?: "#FFFFFF4D",
                tags = (map["tags"] as? List<*>)?.filterIsInstance<String>(),
                slides = (map["slides"] as? List<Map<String, Any?>>)
                    ?.map { StorySlideResponse.fromMap(it) } ?: emptyList(),
                mergeContext = (map["mergeContext"] as? Map<String, String>)
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "token" to token,
        "storyId" to storyId,
        "name" to name,
        "trigger" to trigger,
        "delaySeconds" to delaySeconds,
        "scrollPercentage" to scrollPercentage,
        "endBehavior" to endBehavior,
        "progressIndicatorColor" to progressIndicatorColor,
        "progressIndicatorInactiveColor" to progressIndicatorInactiveColor,
        "tags" to tags,
        "slides" to slides.map { it.toMap() },
        "mergeContext" to mergeContext
    )
}
