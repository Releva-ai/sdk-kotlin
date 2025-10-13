package ai.releva.sdk.types.cart

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a shopping cart
 */
data class Cart(
    val products: List<CartProduct>,
    val orderId: String? = null,
    val cartPaid: Boolean = false
) {
    companion object {
        /**
         * Create an active (unpaid) cart
         */
        fun active(products: List<CartProduct>) = Cart(
            products = products,
            orderId = null,
            cartPaid = false
        )

        /**
         * Create a paid cart with order ID
         */
        fun paid(products: List<CartProduct>, orderId: String) = Cart(
            products = products,
            orderId = orderId,
            cartPaid = true
        )
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "products" to products.map { it.toMap() },
        "orderId" to orderId,
        "cartPaid" to cartPaid
    )

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("products", JSONArray(products.map { JSONObject(it.toMap()) }))
            put("orderId", orderId)
            put("cartPaid", cartPaid)
        }
    }
}
