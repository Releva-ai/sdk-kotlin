package ai.releva.sdk.types.tracking

import ai.releva.sdk.types.event.CustomEvent
import ai.releva.sdk.types.filter.AbstractFilter
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.product.ViewedProduct
import java.net.URLDecoder


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
    internal var attributionUrl: Map<String, Any>? = null

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
    fun recommenderClick(mergeContext: Map<String, String>?): PushRequest {
        recommenderClick = mergeContext?.let { recommenderClickContext(it) }
        return this
    }

    /**
     * Mark this request as the shopper opening a product from Releva search or from the
     * shopping assistant, so the click is credited to that surface. Pass the product's `url`
     * exactly as Releva returned it; the `rlv_*` query parameters on it carry the attribution
     * (`rlv_aid`/`rlv_apid` for search, `rlv_said`/`rlv_sapid` for the assistant, and
     * `rlv_rid` for a product block).
     *
     * The web SDK reads the same parameters from the landing page URL; an app has no landing
     * URL, so it passes the URL of the product the shopper tapped. A null URL, one with no
     * query, or one without those parameters sets nothing.
     */
    fun attributionUrl(url: String?): PushRequest {
        attributionUrl = url?.let { attributionUrlContext(it) }
        return this
    }

    internal companion object {
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        private val RECOMMENDER_KEYS = listOf("rid", "rpid", "rpos", "pfid")

        /**
         * The push context fields for a recommender click. The API validates `rid` as a uuid
         * and `rpos` as a number of at least 1, and drops the whole request on a mismatch, so
         * malformed values are left out rather than sent.
         */
        fun recommenderClickContext(mergeContext: Map<String, String>): Map<String, Any>? {
            val context = buildMap<String, Any> {
                mergeContext["rid"]?.takeIf { UUID_PATTERN.matches(it) }?.let { put("rid", it) }
                mergeContext["rpid"]?.takeIf { it.isNotBlank() }?.let { put("rpid", it) }
                mergeContext["rpos"]?.toIntOrNull()?.takeIf { it >= 1 }?.let { put("rpos", it) }
                mergeContext["pfid"]?.toLongOrNull()?.let { put("pfid", it) }
            }
            // Without the block token there is nothing to credit.
            return context.takeIf { it.containsKey("rid") }
        }

        /**
         * The push context fields the `rlv_*` query parameters of [url] carry. Each surface is
         * credited by its required fields — `aid` and `apid` for search, `said` and `sapid` for
         * the shopping assistant, `rid` for a product block — so a group missing one is left
         * out whole, while a malformed value is dropped on its own, for the reason above.
         *
         * The query is read leniently, as the web SDK does: Releva's own assistant URLs come
         * back with their percent-escapes decoded, which `java.net.URI` would reject, and a
         * decoded `#` can precede the `rlv_*` pairs, so each value is cut at its first `#`
         * rather than the query at the first one.
         */
        fun attributionUrlContext(url: String): Map<String, Any>? {
            val parameters = queryParameters(url)
            val context = buildMap<String, Any> {
                val aid = parameters["rlv_aid"]?.takeIf { it.isNotBlank() }
                val apid = parameters["rlv_apid"]?.takeIf { it.isNotBlank() }
                if (aid != null && apid != null) {
                    put("aid", aid)
                    put("apid", apid)
                    parameters["rlv_apos"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { put("apos", it) }
                    parameters["rlv_pfid"]?.toLongOrNull()?.let { put("pfid", it) }
                }

                val said = parameters["rlv_said"]?.takeIf { it.isNotBlank() }
                val sapid = parameters["rlv_sapid"]?.takeIf { it.isNotBlank() }
                if (said != null && sapid != null) {
                    put("said", said)
                    put("sapid", sapid)
                }

                val recommender = RECOMMENDER_KEYS
                    .mapNotNull { key -> parameters["rlv_$key"]?.let { key to it } }
                    .toMap()
                recommenderClickContext(recommender)?.let { putAll(it) }
            }
            return context.takeIf { it.isNotEmpty() }
        }

        private fun queryParameters(url: String): Map<String, String> {
            // The fragment is cut per value, not off the whole query: a decoded `%23` in a
            // merchant parameter leaves a raw `#` ahead of the appended `rlv_*` pairs.
            val query = url.substringAfter('?', "")
            return query.split("&").mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    try {
                        decode(pair.substring(0, separator)) to
                            decode(pair.substring(separator + 1).substringBefore('#'))
                    } catch (e: IllegalArgumentException) {
                        // A merchant's own parameter may carry a bare `%`; it must not take the
                        // `rlv_*` pairs down with it.
                        null
                    }
                }
            }.toMap()
        }

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}
