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

    /**
     * price × quantity. A missing price contributes 0 and a missing quantity counts as
     * one — the same asymmetry the Swift SDK uses, and for the same reason: "no quantity
     * given" means one of these, while "no price given" means none was reported.
     */
    val totalPrice: Double get() = (price ?: 0.0) * (quantity ?: 1.0)

    /** True when this product reports a price above zero. */
    val hasPrice: Boolean get() = (price ?: 0.0) > 0

}
