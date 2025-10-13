package ai.releva.sdk.types.customfield

import org.json.JSONObject

/**
 * Custom fields container for products and other entities
 */
data class CustomFields(
    private val fields: Map<String, CustomField>
) {
    companion object {
        fun empty() = CustomFields(emptyMap())

        fun fromMap(map: Map<String, Any?>): CustomFields {
            val fields = mutableMapOf<String, CustomField>()
            map.forEach { (key, value) ->
                when (value) {
                    is String -> fields[key] = CustomField.StringField(value)
                    is Number -> fields[key] = CustomField.NumericField(value.toDouble())
                    is Boolean -> fields[key] = CustomField.StringField(value.toString())
                    null -> fields[key] = CustomField.StringField("")
                }
            }
            return CustomFields(fields)
        }
    }

    fun toMap(): Map<String, Any?> {
        return fields.mapValues { (_, field) ->
            when (field) {
                is CustomField.StringField -> field.value
                is CustomField.NumericField -> field.value
            }
        }
    }

    fun toJson(): JSONObject = JSONObject(toMap())
}

/**
 * Sealed class representing different types of custom fields
 */
sealed class CustomField {
    data class StringField(val value: String) : CustomField()
    data class NumericField(val value: Double) : CustomField()
}
