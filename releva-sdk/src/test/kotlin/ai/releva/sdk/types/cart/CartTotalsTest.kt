package ai.releva.sdk.types.cart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CART-09 in the device plan. These existed on the Swift cart and not on this one, so the
 * numbers below are Swift's, including the asymmetry in how missing values are treated: a
 * product with no price contributes nothing to the total, while a product with no quantity
 * counts as one of itself. That reads as inconsistent until you say it out loud — a
 * missing quantity means "one of these" and a missing price means "no price was reported"
 * — and the two SDKs have to agree on it or the same cart totals differently per platform.
 */
class CartTotalsTest {

    private fun product(id: String, price: Double?, quantity: Double?) =
        CartProduct(id = id, price = price, quantity = quantity)

    @Test
    fun `totals over a plain cart`() {
        val cart = Cart.active(listOf(
            product("a", 10.0, 2.0),
            product("b", 5.5, 3.0)
        ))

        assertEquals(2, cart.itemCount)          // distinct products, not units
        assertEquals(5.0, cart.totalQuantity, 0.0001)
        assertEquals(36.5, cart.totalPrice, 0.0001)   // 20 + 16.5
        assertFalse(cart.isEmpty)
    }

    @Test
    fun `a missing quantity counts as one`() {
        val cart = Cart.active(listOf(product("a", 9.99, null)))

        assertEquals(9.99, cart.totalPrice, 0.0001)
        // but it contributes nothing to the quantity total, which sums what was reported
        assertEquals(0.0, cart.totalQuantity, 0.0001)
    }

    @Test
    fun `a missing price contributes nothing`() {
        val cart = Cart.active(listOf(
            product("a", null, 4.0),
            product("b", 2.0, 3.0)
        ))

        assertEquals(6.0, cart.totalPrice, 0.0001)
        assertEquals(7.0, cart.totalQuantity, 0.0001)
        assertFalse(product("a", null, 4.0).hasPrice)
        assertTrue(product("b", 2.0, 3.0).hasPrice)
    }

    @Test
    fun `an empty cart totals zero`() {
        val cart = Cart.active(emptyList())

        assertTrue(cart.isEmpty)
        assertEquals(0, cart.itemCount)
        assertEquals(0.0, cart.totalQuantity, 0.0001)
        assertEquals(0.0, cart.totalPrice, 0.0001)
    }

    @Test
    fun `lookup by product id`() {
        val cart = Cart.active(listOf(product("a", 1.0, 1.0), product("b", 2.0, 1.0)))

        assertTrue(cart.contains("b"))
        assertFalse(cart.contains("zzz"))
        assertEquals(2.0, cart.product("b")?.price)
        assertNull(cart.product("zzz"))
    }

    @Test
    fun `a paid cart totals the same as an active one`() {
        val products = listOf(product("a", 10.0, 2.0))
        val paid = Cart.paid(products, orderId = "order-1")

        assertEquals(20.0, paid.totalPrice, 0.0001)
        assertTrue(paid.cartPaid)
        assertEquals("order-1", paid.orderId)
    }

    @Test
    fun `per-product total`() {
        assertEquals(20.0, product("a", 10.0, 2.0).totalPrice, 0.0001)
        assertEquals(10.0, product("a", 10.0, null).totalPrice, 0.0001)
        assertEquals(0.0, product("a", null, 2.0).totalPrice, 0.0001)
    }
}
