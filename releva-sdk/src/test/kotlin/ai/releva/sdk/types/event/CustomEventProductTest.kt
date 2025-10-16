package ai.releva.sdk.types.event

import org.junit.Assert.*
import org.junit.Test

class CustomEventProductTest {

    @Test
    fun `create custom event product with id and quantity`() {
        val product = CustomEventProduct(id = "product-123", quantity = 2.5)

        assertEquals("product-123", product.id)
        assertEquals(2.5, product.quantity!!, 0.001)
    }

    @Test
    fun `create custom event product with id only`() {
        val product = CustomEventProduct(id = "product-456")

        assertEquals("product-456", product.id)
        assertNull(product.quantity)
    }

    @Test
    fun `toMap includes quantity when present`() {
        val product = CustomEventProduct(id = "prod-1", quantity = 3.0)
        val map = product.toMap()

        assertEquals("prod-1", map["id"])
        assertEquals(3.0, map["quantity"])
    }

    @Test
    fun `toMap excludes quantity when null`() {
        val product = CustomEventProduct(id = "prod-2")
        val map = product.toMap()

        assertEquals("prod-2", map["id"])
        assertFalse(map.containsKey("quantity"))
    }

    @Test
    fun `toMap with zero quantity`() {
        val product = CustomEventProduct(id = "prod-3", quantity = 0.0)
        val map = product.toMap()

        assertEquals("prod-3", map["id"])
        assertEquals(0.0, map["quantity"])
    }
}
