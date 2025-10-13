package ai.releva.sdk.types.filter

/**
 * Abstract base class for all filter types
 */
interface AbstractFilter {
    fun toMap(): Map<String, Any>
}
