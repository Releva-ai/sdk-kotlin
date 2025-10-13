package ai.releva.sdk.types.response

import org.json.JSONObject
import java.util.Date

/**
 * Product recommender response
 */
data class RecommenderResponse(
    val token: String,
    val name: String,
    val meta: Map<String, Any?>? = null,
    val tags: List<String>? = null,
    val cssSelector: String? = null,
    val displayStrategy: String? = null,
    val template: Template? = null,
    val response: List<ProductRecommendation>
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): RecommenderResponse {
            return RecommenderResponse(
                token = map["token"] as? String ?: "",
                name = map["name"] as? String ?: "",
                meta = map["meta"] as? Map<String, Any?>,
                tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String },
                cssSelector = map["cssSelector"] as? String,
                displayStrategy = map["displayStrategy"] as? String,
                template = (map["template"] as? Map<String, Any?>)?.let { Template.fromMap(it) },
                response = (map["response"] as? List<Map<String, Any?>>)
                    ?.map { ProductRecommendation.fromMap(it) } ?: emptyList()
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "token" to token,
        "name" to name,
        "meta" to meta,
        "tags" to tags,
        "cssSelector" to cssSelector,
        "displayStrategy" to displayStrategy,
        "template" to template?.toMap(),
        "response" to response.map { it.toMap() }
    )
}

/**
 * Product recommendation
 */
data class ProductRecommendation(
    val available: Boolean,
    val categories: List<String>? = null,
    val createdAt: Date? = null,
    val currency: String? = null,
    val custom: Map<String, Any?>? = null,
    val data: Map<String, Any?>? = null,
    val description: String? = null,
    val discount: Double? = null,
    val discountPercent: Double? = null,
    val discountPrice: Double? = null,
    val id: String,
    val imageUrl: String? = null,
    val listPrice: Double? = null,
    val locale: String? = null,
    val mergeContext: Map<String, String>? = null,
    val name: String,
    val price: Double,
    val publishedAt: Date? = null,
    val updatedAt: Date? = null,
    val url: String? = null
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): ProductRecommendation {
            return ProductRecommendation(
                available = map["available"] as? Boolean ?: false,
                categories = (map["categories"] as? List<*>)?.mapNotNull { it as? String },
                createdAt = (map["createdAt"] as? String)?.let { parseDate(it) },
                currency = map["currency"] as? String,
                custom = map["custom"] as? Map<String, Any?>,
                data = map["data"] as? Map<String, Any?>,
                description = map["description"] as? String,
                discount = (map["discount"] as? Number)?.toDouble(),
                discountPercent = (map["discountPercent"] as? Number)?.toDouble(),
                discountPrice = (map["discountPrice"] as? Number)?.toDouble(),
                id = map["id"] as? String ?: "",
                imageUrl = map["imageUrl"] as? String,
                listPrice = (map["listPrice"] as? Number)?.toDouble(),
                locale = map["locale"] as? String,
                mergeContext = (map["mergeContext"] as? Map<*, *>)
                    ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: "") } }
                    ?.toMap(),
                name = map["name"] as? String ?: "",
                price = (map["price"] as? Number)?.toDouble() ?: 0.0,
                publishedAt = (map["publishedAt"] as? String)?.let { parseDate(it) },
                updatedAt = (map["updatedAt"] as? String)?.let { parseDate(it) },
                url = map["url"] as? String
            )
        }

        private fun parseDate(dateString: String): Date? {
            return try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(dateString)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "available" to available,
        "categories" to categories,
        "createdAt" to createdAt?.toIso8601String(),
        "currency" to currency,
        "custom" to custom,
        "data" to data,
        "description" to description,
        "discount" to discount,
        "discountPercent" to discountPercent,
        "discountPrice" to discountPrice,
        "id" to id,
        "imageUrl" to imageUrl,
        "listPrice" to listPrice,
        "locale" to locale,
        "mergeContext" to mergeContext,
        "name" to name,
        "price" to price,
        "publishedAt" to publishedAt?.toIso8601String(),
        "updatedAt" to updatedAt?.toIso8601String(),
        "url" to url
    )
}

/**
 * Template for rendering recommendations
 */
data class Template(
    val id: Int,
    val body: String
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): Template {
            return Template(
                id = (map["id"] as? Number)?.toInt() ?: 0,
                body = map["body"] as? String ?: ""
            )
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "body" to body
    )
}

// Extension function to convert Date to ISO 8601 string
private fun Date.toIso8601String(): String {
    return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(this)
}
