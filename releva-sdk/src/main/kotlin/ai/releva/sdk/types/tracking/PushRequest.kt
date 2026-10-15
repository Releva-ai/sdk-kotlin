package ai.releva.sdk.types.tracking

import ai.releva.sdk.types.event.CustomEvent
import ai.releva.sdk.types.filter.AbstractFilter
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.product.ViewedProduct


/**
 * Base class for all push requests to Releva API
 */
class PushRequest {
    internal var pageToken: String? = null
    internal var pageUrl: String? = null
    internal var events: List<CustomEvent>? = null
    internal var pageProductIds: List<String>? = null
    internal var pageCategories: List<String>? = null
    internal var viewedProduct: ViewedProduct? = null
    internal var pageFilter: AbstractFilter? = null
    internal var pageQuery: String? = null
    internal var pageLocale: String? = null
    internal var pageCurrency: String? = null
    internal var cart: Cart? = null
    internal var recommenderClick: Map<String, Any>? = null

    fun getScreenToken(): String? = pageToken

    fun getCustomEvents(): List<CustomEvent>? = events

    /**
     * Set optional screen token (external page identifier)
     */

    fun url(url: String): PushRequest {
        pageUrl = url
        return this
    }

    fun screenToken(token: String): PushRequest {
        pageToken = token
        return this
    }


    fun pageCategories(categories: List<String>): PushRequest {
        pageCategories = categories
        return this
    }

    fun cart(passedCart: Cart): PushRequest {
        cart = passedCart
        return this
    }


    fun pageProductIds(ids: List<String>): PushRequest {
        pageProductIds = ids
        return this
    }


    fun pageFilter(filter: AbstractFilter): PushRequest {
        pageFilter = filter
        return this
    }

    fun pageQuery(query: String): PushRequest {
        pageQuery = query
        return this
    }

    fun locale(locale: String): PushRequest {
        pageLocale = locale
        return this
    }

    fun currency(currency: String): PushRequest {
        pageCurrency = currency
        return this
    }

    /**
     * Set custom events to track user interactions
     */
    fun customEvents(events: List<CustomEvent>): PushRequest {
        this.events = if (events.isEmpty()) null else events
        return this
    }

    fun productView(product: ViewedProduct): PushRequest {
        viewedProduct = product
        return this
    }

    /**
     * Mark this request as the shopper opening a product from a Releva product block, so the
     * click is credited to that block (and, when the feed bandit is on, rewards the feed that
     * filled it). Pass the product's
     * [ai.releva.sdk.types.response.ProductRecommendation.mergeContext] exactly as Releva
     * returned it; it carries the block token (`rid`), product id (`rpid`), position (`rpos`)
     * and feed id (`pfid`).
     *
     * The web SDK reads the same values from the `rlv_*` URL parameters of the landing page;
     * an app has no landing URL, so it sends them with the product view instead.
     */
    fun recommenderClick(mergeContext: Map<String, String>): PushRequest {
        recommenderClick = recommenderClickContext(mergeContext)
        return this
    }

    internal companion object {
        /**
         * The push context fields for a recommender click. The API validates `rid` as a uuid
         * and `rpos` as a number of at least 1, and drops the whole request on a mismatch, so
         * malformed values are left out rather than sent.
         */
        fun recommenderClickContext(mergeContext: Map<String, String>): Map<String, Any>? {
            val context = buildMap<String, Any> {
                mergeContext["rid"]?.takeIf { it.isNotBlank() }?.let { put("rid", it) }
                mergeContext["rpid"]?.takeIf { it.isNotBlank() }?.let { put("rpid", it) }
                mergeContext["rpos"]?.toIntOrNull()?.takeIf { it >= 1 }?.let { put("rpos", it) }
                mergeContext["pfid"]?.toLongOrNull()?.let { put("pfid", it) }
            }
            // Without the block token there is nothing to credit.
            return context.takeIf { it.containsKey("rid") }
        }
    }
}
