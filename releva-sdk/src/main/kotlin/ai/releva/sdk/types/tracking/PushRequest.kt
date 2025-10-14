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
}
