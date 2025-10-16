package ai.releva.sdk.types.cart

import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.customfield.NumericField
import ai.releva.sdk.types.customfield.StringField
import org.junit.Assert.*
import org.junit.Test

class CartProductTest {

    @Test
    fun `create cart product with required fields`() {
        val product = CartProduct(
            id = "product-123",
            price = 99.99,
            quantity = 2.0
        )

        assertEquals("product-123", product.id)
        assertEquals(99.99, product.price!!, 0.001)
        assertEquals(2.0, product.quantity!!, 0.001)
    }

    @Test
    fun `create cart product with null price`() {
        val product = CartProduct(
            id = "product-456",
            price = null,
            quantity = 1.0
        )

        assertEquals("product-456", product.id)
        assertNull(product.price)
        assertEquals(1.0, product.quantity!!, 0.001)
    }

    @Test
    fun `create cart product with custom fields`() {
        val customFields = CustomFields(
            string = listOf(StringField("color", listOf("red"))),
            numeric = listOf(NumericField("size", listOf(42.0)))
        )

        val product = CartProduct(
            id = "product-789",
            price = 149.99,
            quantity = 1.0,
            custom = customFields
        )

        assertEquals("product-789", product.id)
        assertEquals(149.99, product.price!!, 0.001)
        assertEquals(1.0, product.quantity!!, 0.001)
        assertNotNull(product.custom)
        assertEquals(1, product.custom.string.size)
        assertEquals(1, product.custom.numeric.size)
    }

    @Test
    fun `create cart product with empty custom fields`() {
        val product = CartProduct(
            id = "product-001",
            price = 29.99,
            quantity = 3.0,
            custom = CustomFields.empty()
        )

        assertEquals("product-001", product.id)
        assertEquals(29.99, product.price!!, 0.001)
        assertEquals(3.0, product.quantity!!, 0.001)
        assertNotNull(product.custom)
        assertTrue(product.custom.string.isEmpty())
        assertTrue(product.custom.numeric.isEmpty())
        assertTrue(product.custom.date.isEmpty())
    }

    @Test
    fun `cart product with zero quantity`() {
        val product = CartProduct(
            id = "product-zero",
            price = 10.0,
            quantity = 0.0
        )

        assertEquals(10.0, product.price!!, 0.001)
        assertEquals(0.0, product.quantity!!, 0.001)
    }

    @Test
    fun `cart product with fractional quantity`() {
        val product = CartProduct(
            id = "product-frac",
            price = 15.0,
            quantity = 2.5
        )

        assertEquals(15.0, product.price!!, 0.001)
        assertEquals(2.5, product.quantity!!, 0.001)
    }

    @Test
    fun `cart product default custom fields`() {
        val product = CartProduct(
            id = "product-default",
            price = null,
            quantity = 1.0
        )

        assertEquals("product-default", product.id)
        assertNull(product.price)
        assertEquals(1.0, product.quantity!!, 0.001)
        assertNotNull(product.custom)
        assertTrue(product.custom.isEmpty)
    }
}
