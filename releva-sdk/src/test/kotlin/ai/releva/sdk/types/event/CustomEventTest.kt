package ai.releva.sdk.types.event

import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.customfield.NumericField
import ai.releva.sdk.types.customfield.StringField
import org.junit.Assert.*
import org.junit.Test

class CustomEventTest {

    @Test
    fun `create simple custom event with action and tags`() {
        val event = CustomEvent(
            action = "user_logged_in",
            tags = listOf("authentication", "login")
        )

        assertEquals("user_logged_in", event.action)
        assertEquals(2, event.tags.size)
        assertTrue(event.tags.contains("authentication"))
        assertTrue(event.tags.contains("login"))
        assertTrue(event.products.isEmpty())
    }

    @Test
    fun `create custom event with products`() {
        val products = listOf(
            CustomEventProduct(id = "product-123", quantity = 2.0),
            CustomEventProduct(id = "product-456", quantity = 1.0)
        )

        val event = CustomEvent(
            action = "product_added_to_cart",
            products = products,
            tags = listOf("cart")
        )

        assertEquals("product_added_to_cart", event.action)
        assertEquals(2, event.products.size)
        assertEquals("product-123", event.products[0].id)
        assertEquals(2.0, event.products[0].quantity!!, 0.001)
    }

    @Test
    fun `create custom event with custom fields`() {
        val customFields = CustomFields(
            string = listOf(StringField("filter_type", listOf("price_range"))),
            numeric = listOf(NumericField("min_price", listOf(50.0)))
        )

        val event = CustomEvent(
            action = "filter_applied",
            tags = listOf("search", "filter"),
            custom = customFields
        )

        assertEquals("filter_applied", event.action)
        assertNotNull(event.custom)
        assertEquals(1, event.custom.string.size)
        assertEquals(1, event.custom.numeric.size)
    }

    @Test
    fun `toMap converts event to map correctly`() {
        val event = CustomEvent(
            action = "test_event",
            products = listOf(CustomEventProduct(id = "prod-1", quantity = 3.0)),
            tags = listOf("tag1", "tag2")
        )

        val map = event.toMap()

        assertEquals("test_event", map["action"])
        assertEquals(listOf("tag1", "tag2"), map["tags"])
        assertNotNull(map["products"])

        @Suppress("UNCHECKED_CAST")
        val productsList = map["products"] as? List<Map<String, Any?>>
        assertNotNull(productsList)
        assertEquals(1, productsList?.size)
        assertEquals("prod-1", productsList?.get(0)?.get("id"))
    }

    @Test
    fun `toMap excludes custom field if empty`() {
        val event = CustomEvent(
            action = "simple_event",
            tags = listOf("test")
        )

        val map = event.toMap()

        assertFalse(map.containsKey("custom"))
    }

    @Test
    fun `default values are applied correctly`() {
        val event = CustomEvent(action = "minimal_event")

        assertEquals("minimal_event", event.action)
        assertTrue(event.products.isEmpty())
        assertTrue(event.tags.isEmpty())
        assertNotNull(event.custom)
    }
}
