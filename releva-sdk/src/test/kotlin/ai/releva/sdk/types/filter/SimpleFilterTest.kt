package ai.releva.sdk.types.filter

import org.junit.Assert.*
import org.junit.Test

class SimpleFilterTest {

    @Test
    fun `create price range filter`() {
        val filter = SimpleFilter.priceRange(minPrice = 50.0, maxPrice = 200.0)

        val map = filter.toMap()
        assertEquals("price", map["key"])
        assertNotNull(map["value"])
    }

    @Test
    fun `create brand filter`() {
        val filter = SimpleFilter.brand(brand = "Nike")

        val map = filter.toMap()
        assertEquals("custom.string.brand", map["key"])
        assertNotNull(map["value"])
    }

    @Test
    fun `create color filter`() {
        val filter = SimpleFilter.color(color = "red")

        val map = filter.toMap()
        assertEquals("custom.string.color", map["key"])
    }

    @Test
    fun `create size filter`() {
        val filter = SimpleFilter.size(size = "42")

        val map = filter.toMap()
        assertEquals("custom.string.size", map["key"])
    }

    @Test
    fun `create custom filter with eq operator`() {
        val filter = SimpleFilter(
            key = "category",
            operator = FilterOperator.EQ,
            value = "electronics",
            action = FilterAction.INCLUDE
        )

        val map = filter.toMap()
        assertEquals("category", map["key"])
        assertEquals("eq", map["operator"])
        assertEquals("electronics", map["value"])
    }

    @Test
    fun `create custom filter with gt operator`() {
        val filter = SimpleFilter(
            key = "rating",
            operator = FilterOperator.GT,
            value = "4.0",
            action = FilterAction.INCLUDE
        )

        val map = filter.toMap()
        assertEquals("rating", map["key"])
        assertEquals("gt", map["operator"])
        assertEquals("4.0", map["value"])
    }

    @Test
    fun `create custom filter with lt operator`() {
        val filter = SimpleFilter(
            key = "price",
            operator = FilterOperator.LT,
            value = "100.0",
            action = FilterAction.INCLUDE
        )

        val map = filter.toMap()
        assertEquals("price", map["key"])
        assertEquals("lt", map["operator"])
        assertEquals("100.0", map["value"])
    }

    @Test
    fun `create custom filter with gte operator`() {
        val filter = SimpleFilter(
            key = "stock",
            operator = FilterOperator.GTE,
            value = "1",
            action = FilterAction.INCLUDE
        )

        val map = filter.toMap()
        assertEquals("stock", map["key"])
        assertEquals("gte", map["operator"])
    }

    @Test
    fun `create custom filter with lte operator`() {
        val filter = SimpleFilter(
            key = "discount",
            operator = FilterOperator.LTE,
            value = "50",
            action = FilterAction.INCLUDE
        )

        val map = filter.toMap()
        assertEquals("discount", map["key"])
        assertEquals("lte", map["operator"])
    }

    @Test
    fun `filter with action parameter`() {
        val filter = SimpleFilter(
            key = "brand",
            operator = FilterOperator.EQ,
            value = "Adidas",
            action = FilterAction.BOOST
        )

        val map = filter.toMap()
        assertEquals("brand", map["key"])
        assertEquals("boost", map["action"])
    }

    @Test
    fun `filter with multiple actions`() {
        val includeFilter = SimpleFilter(
            key = "available",
            operator = FilterOperator.EQ,
            value = "true",
            action = FilterAction.INCLUDE
        )

        val excludeFilter = SimpleFilter(
            key = "outofstock",
            operator = FilterOperator.EQ,
            value = "true",
            action = FilterAction.EXCLUDE
        )

        assertEquals("include", includeFilter.toMap()["action"])
        assertEquals("exclude", excludeFilter.toMap()["action"])
    }
}
