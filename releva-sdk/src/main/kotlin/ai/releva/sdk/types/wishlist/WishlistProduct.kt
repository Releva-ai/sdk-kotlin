package ai.releva.sdk.types.wishlist

import ai.releva.sdk.types.customfield.CustomFields

/**
 * Represents a product in the wishlist
 *
 * @property id Product ID
 * @property custom Custom fields for the product
 */
data class WishlistProduct(
    val id: String,
    val custom: CustomFields = CustomFields.empty()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "custom" to custom.toMap()
    )
}
