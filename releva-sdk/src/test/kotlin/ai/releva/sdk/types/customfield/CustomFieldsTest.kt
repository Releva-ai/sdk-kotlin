package ai.releva.sdk.types.customfield

import org.junit.Assert.*
import org.junit.Test

class CustomFieldsTest {

    @Test
    fun `create empty custom fields`() {
        val customFields = CustomFields.empty()

        assertTrue(customFields.isEmpty)
        assertTrue(customFields.string.isEmpty())
        assertTrue(customFields.numeric.isEmpty())
        assertTrue(customFields.date.isEmpty())
    }

    @Test
    fun `create custom fields with string fields`() {
        val stringFields = listOf(
            StringField("color", listOf("red", "blue")),
            StringField("material", listOf("cotton"))
        )

        val customFields = CustomFields(
            string = stringFields,
            numeric = emptyList(),
            date = emptyList()
        )

        assertFalse(customFields.isEmpty)
        assertEquals(2, customFields.string.size)
        assertTrue(customFields.numeric.isEmpty())
        assertTrue(customFields.date.isEmpty())
    }

    @Test
    fun `create custom fields with numeric fields`() {
        val numericFields = listOf(
            NumericField("rating", listOf(4.5, 5.0)),
            NumericField("size", listOf(42.0))
        )

        val customFields = CustomFields(
            string = emptyList(),
            numeric = numericFields,
            date = emptyList()
        )

        assertFalse(customFields.isEmpty)
        assertTrue(customFields.string.isEmpty())
        assertEquals(2, customFields.numeric.size)
        assertTrue(customFields.date.isEmpty())
    }

    @Test
    fun `create custom fields with date fields`() {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val dateFields = listOf(
            DateField("created_at", listOf(formatter.parse("2025-01-01")!!)),
            DateField("updated_at", listOf(formatter.parse("2025-01-15")!!))
        )

        val customFields = CustomFields(
            string = emptyList(),
            numeric = emptyList(),
            date = dateFields
        )

        assertFalse(customFields.isEmpty)
        assertTrue(customFields.string.isEmpty())
        assertTrue(customFields.numeric.isEmpty())
        assertEquals(2, customFields.date.size)
    }

    @Test
    fun `create custom fields with mixed types`() {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val customFields = CustomFields(
            string = listOf(StringField("category", listOf("electronics"))),
            numeric = listOf(NumericField("price", listOf(99.99))),
            date = listOf(DateField("release_date", listOf(formatter.parse("2025-10-16")!!)))
        )

        assertFalse(customFields.isEmpty)
        assertEquals(1, customFields.string.size)
        assertEquals(1, customFields.numeric.size)
        assertEquals(1, customFields.date.size)
    }

    @Test
    fun `toMap converts custom fields correctly`() {
        val customFields = CustomFields(
            string = listOf(StringField("brand", listOf("Nike"))),
            numeric = listOf(NumericField("rating", listOf(4.5))),
            date = emptyList()
        )

        val map = customFields.toMap()

        assertNotNull(map["string"])
        assertNotNull(map["numeric"])
        assertFalse(map.containsKey("date"))
    }

    @Test
    fun `toMap empty custom fields returns empty map`() {
        val customFields = CustomFields.empty()
        val map = customFields.toMap()

        assertTrue(map.isEmpty())
    }

    @Test
    fun `string field with multiple values`() {
        val field = StringField("tags", listOf("new", "sale", "featured"))

        assertEquals("tags", field.key)
        assertEquals(3, field.values.size)
        assertTrue(field.values.contains("new"))
        assertTrue(field.values.contains("sale"))
        assertTrue(field.values.contains("featured"))
    }

    @Test
    fun `numeric field with multiple values`() {
        val field = NumericField("sizes", listOf(40.0, 41.0, 42.0, 43.0))

        assertEquals("sizes", field.key)
        assertEquals(4, field.values.size)
        assertEquals(40.0, field.values[0], 0.001)
        assertEquals(43.0, field.values[3], 0.001)
    }

    @Test
    fun `date field with multiple values`() {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val dates = listOf("2025-01-01", "2025-01-02", "2025-01-03").map { formatter.parse(it)!! }
        val field = DateField("available_dates", dates)

        assertEquals("available_dates", field.key)
        assertEquals(3, field.values.size)
    }

    @Test
    fun `custom fields isEmpty property`() {
        val emptyFields = CustomFields.empty()
        val nonEmptyFields = CustomFields(
            string = listOf(StringField("test", listOf("value"))),
            numeric = emptyList(),
            date = emptyList()
        )

        assertTrue(emptyFields.isEmpty)
        assertFalse(nonEmptyFields.isEmpty)
    }
}
