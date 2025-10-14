package ai.releva.sdk.types.event

/**
 * Product data for custom events
 *
 * @property id Product ID
 * @property quantity Optional quantity
 */
data class CustomEventProduct(
    val id: String,
    val quantity: Double? = null
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        quantity?.let { put("quantity", it) }
    }
}
