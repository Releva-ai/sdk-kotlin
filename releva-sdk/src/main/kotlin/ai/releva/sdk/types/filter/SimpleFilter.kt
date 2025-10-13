package ai.releva.sdk.types.filter

/**
 * Filter operators for comparing values
 */
enum class FilterOperator(val value: String) {
    EQ("eq"),           // Equal to
    LT("lt"),           // Less than
    GT("gt"),           // Greater than
    LTE("lte"),         // Less than or equal to
    GTE("gte"),         // Greater than or equal to
    GTE_LTE("gte,lte"), // Greater than or equal to AND less than or equal to
    GTE_LT("gte,lt"),   // Greater than or equal to AND less than
    GT_LTE("gt,lte"),   // Greater than AND less than or equal to
    GT_LT("gt,lt")      // Greater than AND less than
}

/**
 * Filter actions for product filtering
 */
enum class FilterAction(val value: String) {
    INCLUDE("include"),  // Include only products that match the condition
    EXCLUDE("exclude"),  // Exclude products that match the condition
    BURY("bury"),        // Make products appear at bottom if they match
    BOOST("boost")       // Make products appear at top if they match
}

/**
 * Simple filter for single field conditions
 *
 * @property key Field key to filter on
 * @property operator Comparison operator
 * @property value Value to compare against
 * @property action Action to take for matching products
 * @property weight Optional weight for the filter
 */
data class SimpleFilter(
    val key: String,
    val operator: FilterOperator,
    val value: String,
    val action: FilterAction,
    val weight: Int? = null
) : AbstractFilter {

    companion object {
        /**
         * Create a filter for standard fields (e.g., price, name, etc.)
         */
        fun standardField(
            fieldName: String,
            operator: FilterOperator,
            value: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = SimpleFilter(
            key = fieldName,
            operator = operator,
            value = value,
            action = action,
            weight = weight
        )

        /**
         * Create a filter for custom string fields
         */
        fun customString(
            fieldName: String,
            operator: FilterOperator,
            value: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = SimpleFilter(
            key = "custom.string.$fieldName",
            operator = operator,
            value = value,
            action = action,
            weight = weight
        )

        /**
         * Create a filter for custom numeric fields
         */
        fun customNumeric(
            fieldName: String,
            operator: FilterOperator,
            value: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = SimpleFilter(
            key = "custom.numeric.$fieldName",
            operator = operator,
            value = value,
            action = action,
            weight = weight
        )

        /**
         * Create a filter for custom date fields
         */
        fun customDate(
            fieldName: String,
            operator: FilterOperator,
            value: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = SimpleFilter(
            key = "custom.date.$fieldName",
            operator = operator,
            value = value,
            action = action,
            weight = weight
        )

        /**
         * Create a price range filter (common use case)
         */
        fun priceRange(
            minPrice: Double,
            maxPrice: Double,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = SimpleFilter(
            key = "price",
            operator = FilterOperator.GTE_LTE,
            value = "$minPrice,$maxPrice",
            action = action,
            weight = weight
        )

        /**
         * Create a size filter (common use case)
         */
        fun size(
            size: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = customString(
            fieldName = "size",
            operator = FilterOperator.EQ,
            value = size,
            action = action,
            weight = weight
        )

        /**
         * Create a brand filter (common use case)
         */
        fun brand(
            brand: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = customString(
            fieldName = "brand",
            operator = FilterOperator.EQ,
            value = brand,
            action = action,
            weight = weight
        )

        /**
         * Create a color filter (common use case)
         */
        fun color(
            color: String,
            action: FilterAction = FilterAction.INCLUDE,
            weight: Int? = null
        ) = customString(
            fieldName = "color",
            operator = FilterOperator.EQ,
            value = color,
            action = action,
            weight = weight
        )
    }

    override fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "key" to key,
            "operator" to operator.value,
            "value" to value,
            "action" to action.value
        )
        weight?.let { map["weight"] = it.toString() }
        return map
    }
}
