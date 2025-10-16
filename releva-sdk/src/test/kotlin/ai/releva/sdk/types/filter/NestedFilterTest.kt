package ai.releva.sdk.types.filter

import org.junit.Assert.*
import org.junit.Test

class NestedFilterTest {

    @Test
    fun `create AND filter with multiple conditions`() {
        val filters = listOf(
            SimpleFilter.brand(brand = "Nike"),
            SimpleFilter.color(color = "red")
        )

        val nestedFilter = NestedFilter.and(filters)

        val map = nestedFilter.toMap()
        assertEquals("and", map["operator"])
        assertNotNull(map["nested"])

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>
        assertNotNull(filtersList)
        assertEquals(2, filtersList?.size)
    }

    @Test
    fun `create OR filter with multiple conditions`() {
        val filters = listOf(
            SimpleFilter.brand(brand = "Nike"),
            SimpleFilter.brand(brand = "Adidas")
        )

        val nestedFilter = NestedFilter.or(filters)

        val map = nestedFilter.toMap()
        assertEquals("or", map["operator"])
        assertNotNull(map["nested"])

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>
        assertNotNull(filtersList)
        assertEquals(2, filtersList?.size)
    }

    @Test
    fun `create complex nested filter with AND and OR`() {
        val orFilter = NestedFilter.or(listOf(
            SimpleFilter.brand(brand = "Nike"),
            SimpleFilter.brand(brand = "Adidas")
        ))

        val andFilter = NestedFilter.and(listOf(
            SimpleFilter.priceRange(minPrice = 50.0, maxPrice = 200.0),
            orFilter,
            SimpleFilter.color(color = "red")
        ))

        val map = andFilter.toMap()
        assertEquals("and", map["operator"])

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>
        assertNotNull(filtersList)
        assertEquals(3, filtersList?.size)

        // Check that second filter is nested OR
        val secondFilter = filtersList?.get(1)
        assertEquals("or", secondFilter?.get("operator"))
    }

    @Test
    fun `nested filter with single condition`() {
        val filters = listOf(
            SimpleFilter.brand(brand = "Nike")
        )

        val nestedFilter = NestedFilter.and(filters)

        val map = nestedFilter.toMap()
        assertEquals("and", map["operator"])

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>
        assertEquals(1, filtersList?.size)
    }

    @Test
    fun `deeply nested filters`() {
        val innerOr = NestedFilter.or(listOf(
            SimpleFilter.size(size = "42"),
            SimpleFilter.size(size = "43")
        ))

        val middleAnd = NestedFilter.and(listOf(
            innerOr,
            SimpleFilter.color(color = "red")
        ))

        val outerOr = NestedFilter.or(listOf(
            middleAnd,
            SimpleFilter.brand(brand = "Nike")
        ))

        val map = outerOr.toMap()
        assertEquals("or", map["operator"])

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>
        assertNotNull(filtersList)
        assertEquals(2, filtersList?.size)

        // First item should be a nested AND filter
        val firstFilter = filtersList?.get(0) as? Map<String, Any?>
        assertEquals("and", firstFilter?.get("operator"))
    }

    @Test
    fun `nested filter preserves all filter properties`() {
        val filters = listOf(
            SimpleFilter(
                key = "brand",
                operator = FilterOperator.EQ,
                value = "Nike",
                action = FilterAction.BOOST
            ),
            SimpleFilter(
                key = "available",
                operator = FilterOperator.EQ,
                value = "true",
                action = FilterAction.INCLUDE
            )
        )

        val nestedFilter = NestedFilter.and(filters)
        val map = nestedFilter.toMap()

        @Suppress("UNCHECKED_CAST")
        val filtersList = map["nested"] as? List<Map<String, Any?>>

        val firstFilter = filtersList?.get(0)
        assertEquals("brand", firstFilter?.get("key"))
        assertEquals("boost", firstFilter?.get("action"))

        val secondFilter = filtersList?.get(1)
        assertEquals("available", secondFilter?.get("key"))
        assertEquals("include", secondFilter?.get("action"))
    }
}
