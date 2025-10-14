package ai.releva.sdk.types.response

import org.json.JSONObject

/**
 * Response from Releva API containing recommenders and banners
 *
 * @property recommenders List of product recommenders
 * @property banners List of banners
 * @property push Push notification information
 */
data class RelevaResponse(
    val recommenders: List<RecommenderResponse>,
    val banners: List<BannerResponse>,
    val push: PushInfo? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): RelevaResponse {
            @Suppress("UNCHECKED_CAST")
            return RelevaResponse(
                recommenders = (map["recommenders"] as? List<Map<String, Any?>>)
                    ?.map { RecommenderResponse.fromMap(it) } ?: emptyList(),
                banners = (map["banners"] as? List<Map<String, Any?>>)
                    ?.map { BannerResponse.fromMap(it) } ?: emptyList(),
                push = (map["push"] as? Map<String, Any?>)?.let { PushInfo.fromMap(it) }
            )
        }

        fun fromJson(json: String): RelevaResponse {
            val jsonObject = JSONObject(json)
            return fromMap(jsonObjectToMap(jsonObject))
        }

        private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObject.get(key)
                map[key] = when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    JSONObject.NULL -> null
                    else -> value
                }
            }
            return map
        }
    }

    /**
     * Check if there are any recommenders available
     */
    val hasRecommenders: Boolean
        get() = recommenders.isNotEmpty()

    /**
     * Check if there are any banners available
     */
    val hasBanners: Boolean
        get() = banners.isNotEmpty()

    /**
     * Get recommenders by tag
     */
    fun getRecommendersByTag(tag: String): List<RecommenderResponse> {
        return recommenders.filter { it.tags?.contains(tag) == true }
    }

    /**
     * Get banners by tag
     */
    fun getBannersByTag(tag: String): List<BannerResponse> {
        return banners.filter { it.tags?.contains(tag) == true }
    }

    /**
     * Get recommender by token
     */
    fun getRecommenderByToken(token: String): RecommenderResponse? {
        return recommenders.firstOrNull { it.token == token }
    }

    /**
     * Get banner by token
     */
    fun getBannerByToken(token: String): BannerResponse? {
        return banners.firstOrNull { it.token == token }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "recommenders" to recommenders.map { it.toMap() },
        "banners" to banners.map { it.toMap() },
        "push" to push?.toMap()
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
