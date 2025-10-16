package ai.releva.sdk.types.cart

import ai.releva.sdk.types.customfield.CustomFields
import org.junit.Assert.*
import org.junit.Test

class CartTest {

    @Test
    fun `create active cart with products`() {
        val products = listOf(
            CartProduct(id = "prod-1", price = 99.99, quantity = 1.0),
            CartProduct(id = "prod-2", price = 49.99, quantity = 2.0)
        )

        val cart = Cart.active(products)

        assertFalse(cart.cartPaid)
        assertNull(cart.orderId)
        assertEquals(2, cart.products.size)
        assertEquals("prod-1", cart.products[0].id)
    }

    @Test
    fun `create paid cart with order id`() {
        val products = listOf(
            CartProduct(id = "prod-1", price = 99.99, quantity = 1.0)
        )

        val cart = Cart.paid(products, orderId = "order-12345")

        assertTrue(cart.cartPaid)
        assertEquals("order-12345", cart.orderId)
        assertEquals(1, cart.products.size)
    }

    @Test
    fun `create empty active cart`() {
        val cart = Cart.active(emptyList())

        assertFalse(cart.cartPaid)
        assertNull(cart.orderId)
        assertTrue(cart.products.isEmpty())
    }

    @Test
    fun `paid cart requires order id`() {
        val products = listOf(
            CartProduct(id = "prod-1", price = 99.99, quantity = 1.0)
        )

        val cart = Cart.paid(products, orderId = "order-999")

        assertTrue(cart.cartPaid)
        assertNotNull(cart.orderId)
        assertEquals("order-999", cart.orderId)
    }

    @Test
    fun `cart with multiple products`() {
        val products = listOf(
            CartProduct(id = "p1", price = 10.0, quantity = 1.0),
            CartProduct(id = "p2", price = 20.0, quantity = 2.0),
            CartProduct(id = "p3", price = 30.0, quantity = 3.0)
        )

        val cart = Cart.active(products)

        assertEquals(3, cart.products.size)
        assertEquals(20.0, cart.products[1].price!!, 0.001)
        assertEquals(3.0, cart.products[2].quantity!!, 0.001)
    }
}
