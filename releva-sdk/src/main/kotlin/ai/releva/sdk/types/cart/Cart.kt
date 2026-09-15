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

    // The Swift SDK has carried these since its cart model was written and the Kotlin one
    // never did, so an integrator porting between the two finds the same cart cannot
    // answer the same questions. The defaults below are Swift's and are deliberately not
    // symmetrical: a product with no price contributes nothing, while a product with no
    // quantity counts as one, because a missing quantity means "one of these" and a
    // missing price means "no price was reported" rather than "free".

    /** True when the cart holds no products. */
    val isEmpty: Boolean get() = products.isEmpty()

    /** Number of distinct products, regardless of their quantities. */
    val itemCount: Int get() = products.size

    /** Sum of every product's quantity; a product with no quantity contributes 0. */
    val totalQuantity: Double get() = products.sumOf { it.quantity ?: 0.0 }

    /** Sum of price × quantity; no price contributes 0, no quantity counts as one. */
    val totalPrice: Double get() = products.sumOf { it.totalPrice }

    /** True when a product with [productId] is in the cart. */
    fun contains(productId: String): Boolean = products.any { it.id == productId }

    /** The product with [productId], or null. */
    fun product(productId: String): CartProduct? = products.firstOrNull { it.id == productId }
}
