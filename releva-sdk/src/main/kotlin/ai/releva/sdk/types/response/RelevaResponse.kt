package ai.releva.sdk.types.response

import org.json.JSONObject

/**
 * Response from Releva API containing recommenders, banners, stories, and NPS config
 */
data class RelevaResponse(
    val recommenders: List<RecommenderResponse>,
    val banners: List<BannerResponse>,
    val stories: List<StoryResponse> = emptyList(),
    val push: PushInfo? = null,
    val nps: NpsConfig? = null
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): RelevaResponse {
            return RelevaResponse(
                recommenders = (map["recommenders"] as? List<Map<String, Any?>>)
                    ?.map { RecommenderResponse.fromMap(it) } ?: emptyList(),
                banners = (map["banners"] as? List<Map<String, Any?>>)
                    ?.map { BannerResponse.fromMap(it) } ?: emptyList(),
                stories = (map["stories"] as? List<Map<String, Any?>>)
                    ?.map { StoryResponse.fromMap(it) } ?: emptyList(),
                push = (map["push"] as? Map<String, Any?>)?.let { PushInfo.fromMap(it) },
                nps = (map["nps"] as? Map<String, Any?>)?.let { NpsConfig.fromMap(it) }
            )
        }

        fun fromJson(json: String): RelevaResponse {
            val jsonObject = JSONObject(json)
            return fromMap(jsonObjectToMap(jsonObject))
        }

        fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObject.get(key)
                map[key] = when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is org.json.JSONArray -> jsonArrayToList(value)
                    JSONObject.NULL -> null
                    else -> value
                }
            }
            return map
        }

        private fun jsonArrayToList(jsonArray: org.json.JSONArray): List<Any?> {
            val list = mutableListOf<Any?>()
            for (i in 0 until jsonArray.length()) {
                val value = jsonArray.get(i)
                list.add(when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is org.json.JSONArray -> jsonArrayToList(value)
                    JSONObject.NULL -> null
                    else -> value
                })
            }
            return list
        }
    }

    val hasRecommenders: Boolean get() = recommenders.isNotEmpty()
    val hasBanners: Boolean get() = banners.isNotEmpty()
    val hasStories: Boolean get() = stories.isNotEmpty()

    fun getRecommendersByTag(tag: String) = recommenders.filter { it.tags?.contains(tag) == true }
    fun getBannersByTag(tag: String) = banners.filter { it.tags?.contains(tag) == true }
    fun getStoriesByTag(tag: String) = stories.filter { it.tags?.contains(tag) == true }

    fun getRecommenderByToken(token: String) = recommenders.firstOrNull { it.token == token }
    fun getBannerByToken(token: String) = banners.firstOrNull { it.token == token }
    fun getStoryByToken(token: String) = stories.firstOrNull { it.token == token }

    fun toMap(): Map<String, Any?> = mapOf(
        "recommenders" to recommenders.map { it.toMap() },
        "banners" to banners.map { it.toMap() },
        "stories" to stories.map { it.toMap() },
        "push" to push?.toMap(),
        "nps" to nps?.toMap()
    )
}

/**
 * Push notification information
 */
data class PushInfo(
    val vapidPublicKey: String?
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): PushInfo {
            return PushInfo(
                vapidPublicKey = map["vapidPublicKey"] as? String
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "vapidPublicKey" to vapidPublicKey
    )
}
