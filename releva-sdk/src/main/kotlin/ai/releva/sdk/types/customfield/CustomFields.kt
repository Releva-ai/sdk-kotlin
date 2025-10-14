package ai.releva.sdk.types.customfield

import org.json.JSONObject

/**
 * Custom fields container for products and other entities
 * Supports numeric, string, and date fields in the format:
 * { "numeric": [{"key": "...", "values": [1, 2, 3]}], "string": [...], "date": [...] }
 */
data class CustomFields(
    val numeric: List<NumericField> = emptyList(),
    val string: List<StringField> = emptyList(),
    val date: List<DateField> = emptyList()
) {
    companion object {
        fun empty() = CustomFields()

        fun fromMap(map: Map<String, Any?>): CustomFields {
            val numericFields = mutableListOf<NumericField>()
            val stringFields = mutableListOf<StringField>()
            val dateFields = mutableListOf<DateField>()

            @Suppress("UNCHECKED_CAST")
            map["numeric"]?.let { numericList ->
                (numericList as? List<Map<String, Any?>>)?.forEach { field ->
                    val key = field["key"] as? String ?: return@forEach
                    val values = (field["values"] as? List<Number>)?.map { it.toDouble() } ?: emptyList()
                    numericFields.add(NumericField(key, values))
                }
            }

            @Suppress("UNCHECKED_CAST")
            map["string"]?.let { stringList ->
                (stringList as? List<Map<String, Any?>>)?.forEach { field ->
                    val key = field["key"] as? String ?: return@forEach
                    val values = (field["values"] as? List<String>) ?: emptyList()
                    stringFields.add(StringField(key, values))
                }
            }

            @Suppress("UNCHECKED_CAST")
            map["date"]?.let { dateList ->
                (dateList as? List<Map<String, Any?>>)?.forEach { field ->
                    val key = field["key"] as? String ?: return@forEach
                    val valueStrings = (field["values"] as? List<String>) ?: emptyList()

                    // Parse ISO-8601 date strings to Date objects
                    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")

                    val dates = valueStrings.mapNotNull { dateString ->
                        try {
                            formatter.parse(dateString)
                        } catch (e: Exception) {
                            null // Skip invalid date strings
                        }
                    }

                    if (dates.isNotEmpty()) {
                        dateFields.add(DateField(key, dates))
                    }
                }
            }

            return CustomFields(numericFields, stringFields, dateFields)
        }
    }

    fun toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        if (numeric.isNotEmpty()) {
            result["numeric"] = numeric.map { it.toMap() }
        }

        if (string.isNotEmpty()) {
            result["string"] = string.map { it.toMap() }
        }

        if (date.isNotEmpty()) {
            result["date"] = date.map { it.toMap() }
        }

        return result
    }

    fun toJson(): JSONObject = JSONObject(toMap())
}

/**
 * Numeric custom field with key and numeric values
 */
data class NumericField(
    val key: String,
    val values: List<Double>
) {
    fun toMap(): Map<String, Any> = mapOf(
        "key" to key,
        "values" to values
    )
}

/**
 * String custom field with key and string values
 */
data class StringField(
    val key: String,
    val values: List<String>
) {
    fun toMap(): Map<String, Any> = mapOf(
        "key" to key,
        "values" to values
    )
}

/**
 * Date custom field with key and Date objects
 * Date objects are automatically converted to ISO-8601 strings during serialization
 */
data class DateField(
    val key: String,
    val values: List<java.util.Date>
) {
    fun toMap(): Map<String, Any> {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")

        return mapOf(
            "key" to key,
            "values" to values.map { formatter.format(it) }
        )
    }
}
