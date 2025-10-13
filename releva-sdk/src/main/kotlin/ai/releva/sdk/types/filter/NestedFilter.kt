package ai.releva.sdk.types.filter

/**
 * Nested filter operation types
 */
enum class NestedFilterOperation(val value: String) {
    AND("and"),
    OR("or")
}

/**
 * Nested filter for combining multiple filters with AND/OR logic
 *
 * @property operation The operation type (AND or OR)
 * @property nested List of filters to combine
 */
data class NestedFilter(
    val operation: NestedFilterOperation,
    val nested: List<AbstractFilter>
) : AbstractFilter {

    companion object {
        /**
         * Create an AND filter combining multiple filters
         */
        fun and(filters: List<AbstractFilter>) = NestedFilter(
            operation = NestedFilterOperation.AND,
            nested = filters
        )

        /**
         * Create an OR filter combining multiple filters
         */
        fun or(filters: List<AbstractFilter>) = NestedFilter(
            operation = NestedFilterOperation.OR,
            nested = filters
        )
    }

    override fun toMap(): Map<String, Any> = mapOf(
        "operator" to operation.value,
        "nested" to nested.map { it.toMap() }
    )
}
