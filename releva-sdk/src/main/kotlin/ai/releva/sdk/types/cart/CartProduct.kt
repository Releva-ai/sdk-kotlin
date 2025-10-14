package ai.releva.sdk.types.cart

import ai.releva.sdk.types.customfield.CustomFields
import org.json.JSONObject

/**
 * Represents a product in the shopping cart
 *
 * @property id Product ID
 * @property price Product price
 * @property quantity Product quantity
 * @property custom Custom fields for the product
 */
data class CartProduct(
    val id: String,
    val price: Double?,
    val quantity: Double?,
    val custom: CustomFields = CustomFields.empty()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "price" to price,
        "quantity" to quantity,
        "custom" to custom.toMap()
    )
}
