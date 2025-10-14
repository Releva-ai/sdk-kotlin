package ai.releva.sdk.types.event

import ai.releva.sdk.types.customfield.CustomFields

/**
 * Custom event to track user interactions
 *
 * @property action Event action/name
 * @property products List of products associated with the event
 * @property tags Optional tags for categorizing the event
 * @property custom Optional custom fields
 */
data class CustomEvent(
    val action: String,
    val products: List<CustomEventProduct> = emptyList(),
    val tags: List<String> = emptyList(),
    val custom: CustomFields = CustomFields.empty()
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("action", action)
        put("tags", tags)
        put("products", products.map { it.toMap() })
        val customMap = custom.toMap()
        if (customMap.isNotEmpty()) {
            put("custom", customMap)
        }
    }
}
