package ai.releva.sdk.types.product

data class ViewedProduct(
    val productId: String,
    val custom: ai.releva.sdk.types.customfield.CustomFields
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to productId,
        "custom" to custom.toMap()
    )
}
