package ai.releva.sdk.types.tracking

import ai.releva.sdk.types.filter.AbstractFilter

/**
 * Base class for all push requests to Releva API
 */
abstract class PushRequest {
    protected val data = mutableMapOf<String, Any?>()

    abstract fun getScreenToken(): String?

    open fun toMap(): Map<String, Any?> = data

    fun locale(locale: String): PushRequest {
        data["locale"] = locale
        return this
    }

    fun currency(currency: String): PushRequest {
        data["currency"] = currency
        return this
    }
}

/**
 * Screen view tracking request
 */
class ScreenViewRequest(
    private val screenToken: String,
    productIds: List<String>? = null,
    categories: List<String>? = null,
    filter: AbstractFilter? = null,
    blocks: Map<String, List<String>>? = null
) : PushRequest() {

    init {
        data["screenToken"] = screenToken
        productIds?.let { data["productIds"] = it }
        categories?.let { data["categories"] = it }
        filter?.let { data["filter"] = it.toMap() }
        blocks?.let { data["blocks"] = it }
    }

    override fun getScreenToken(): String = screenToken

    fun productView(viewedProduct: ViewedProduct): ScreenViewRequest {
        data["productView"] = viewedProduct.toMap()
        return this
    }
}

/**
 * Search view tracking request
 */
class SearchRequest(
    private val screenToken: String,
    query: String? = null,
    resultProductIds: List<String>? = null,
    filter: AbstractFilter? = null,
    blocks: Map<String, List<String>>? = null
) : PushRequest() {

    init {
        data["screenToken"] = screenToken
        data["search"] = mutableMapOf<String, Any?>().apply {
            query?.let { this["query"] = it }
            resultProductIds?.let { this["resultProductIds"] = it }
        }
        filter?.let { data["filter"] = it.toMap() }
        blocks?.let { data["blocks"] = it }
    }

    override fun getScreenToken(): String = screenToken
}

/**
 * Checkout success tracking request
 */
class CheckoutSuccessRequest(
    private val screenToken: String,
    orderedCart: ai.releva.sdk.types.cart.Cart,
    userEmail: String? = null,
    userPhoneNumber: String? = null,
    userFirstName: String? = null,
    userLastName: String? = null,
    userRegisteredAt: java.util.Date? = null
) : PushRequest() {

    init {
        data["screenToken"] = screenToken
        data["checkoutSuccess"] = mutableMapOf<String, Any?>().apply {
            put("orderedCart", orderedCart.toMap())
            userEmail?.let { this["userEmail"] = it }
            userPhoneNumber?.let { this["userPhoneNumber"] = it }
            userFirstName?.let { this["userFirstName"] = it }
            userLastName?.let { this["userLastName"] = it }
            userRegisteredAt?.let {
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                this["userRegisteredAt"] = formatter.format(it)
            }
        }
    }

    override fun getScreenToken(): String = screenToken
}

/**
 * Viewed product data
 */
data class ViewedProduct(
    val productId: String,
    val custom: ai.releva.sdk.types.customfield.CustomFields
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "productId" to productId,
        "custom" to custom.toMap()
    )
}
