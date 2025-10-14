package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.engagement.EngagementTrackingService
import ai.releva.sdk.services.storage.StorageService
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.device.DeviceType
import ai.releva.sdk.types.filter.AbstractFilter
import ai.releva.sdk.types.response.BannerResponse
import ai.releva.sdk.types.response.RelevaResponse
import ai.releva.sdk.types.tracking.*
import ai.releva.sdk.types.product.ViewedProduct
import ai.releva.sdk.types.wishlist.WishlistProduct
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Main client for interacting with Releva API
 *
 * @property realm Releva realm (usually empty string)
 * @property accessToken Releva access token
 * @property config SDK configuration
 */
class RelevaClient(
    private val context: Context,
    private val realm: String,
    private val accessToken: String,
    private val config: RelevaConfig = RelevaConfig.full()
) {
    private val storage = StorageService.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mergeProfileIds = mutableListOf<String>()
    private var cartChanged = false
    private var wishlistChanged = false
    private var deviceIdChanged = false
    private var profileChanged = false

    var engagementTrackingService: EngagementTrackingService? = null
        private set

    companion object {
        private const val TAG = "RelevaClient"
        private const val VERSION = "0.0.1-kotlin"
        private const val SESSION_DURATION_MS = 24 * 3600 * 1000L // 1 day
    }

    /**
     * Set profile ID
     */
    suspend fun setProfileId(profileId: String) = withContext(Dispatchers.IO) {
        val previousProfileId = storage.getProfileId()

        if (previousProfileId == null || previousProfileId != profileId) {
            profileChanged = true
            storage.setProfileId(profileId)
            if (previousProfileId != null) {
                mergeProfileIds.add(previousProfileId)
            }
        }
    }

    /**
     * Set device ID
     */
    suspend fun setDeviceId(deviceId: String) = withContext(Dispatchers.IO) {
        val previousDeviceId = storage.getDeviceId()

        if (previousDeviceId == null || previousDeviceId != deviceId) {
            storage.setDeviceId(deviceId)
            deviceIdChanged = true
        }
    }

    /**
     * Enable push engagement tracking
     */
    fun enablePushEngagementTracking() {
        engagementTrackingService = EngagementTrackingService(context)
        engagementTrackingService?.initialize()
    }

    /**
     * Set cart
     */
    suspend fun setCart(cart: Cart) = withContext(Dispatchers.IO) {
        val currentCart = JSONObject(cart.toMap()).toString()
        val previousCart = storage.getCartData()

        if (previousCart == null || previousCart != currentCart) {
            storage.setCartData(currentCart)
            cartChanged = true
        }
    }

    /**
     * Set wishlist
     */
    suspend fun setWishlist(wishlistProducts: List<WishlistProduct>) = withContext(Dispatchers.IO) {
        val currentWishlist = wishlistProducts.map { JSONObject(it.toMap()).toString() }
        val previousWishlist = storage.getWishlistData()

        if (previousWishlist == null || currentWishlist != previousWishlist) {
            storage.setWishlistData(currentWishlist)
            wishlistChanged = true
        }
    }

    /**
     * Register push token
     */
    suspend fun registerPushToken(type: DeviceType, token: String) = withContext(Dispatchers.IO) {
        val deviceId = storage.getDeviceId()
            ?: throw Exception("Please provide deviceId using client.setDeviceId() before using the client!")

        val request = JSONObject().apply {
            put("profileId", storage.getProfileId())
            put("deviceType", type.toApiValue())
            put("deviceId", deviceId)
            put("pushToken", token)
        }

        val response = executeRequest(
            endpoint = "/api/v0/appPush/tokens",
            body = request
        )

        if (response.code != 202) {
            throw Exception("Backend API error: ${response.code} - ${response.body}")
        }

        cartChanged = false
        wishlistChanged = false
        profileChanged = false
        deviceIdChanged = false
        mergeProfileIds.clear()
    }

    /**
     * Track banner click
     */
    suspend fun bannerClicked(banner: BannerResponse, action: String? = null) = withContext(Dispatchers.IO) {
        val context = JSONObject().apply {
            put("deviceId", storage.getDeviceId())
            put("sessionId", getSessionId())
            put("profile", JSONObject().apply {
                put("id", storage.getProfileId())
            })
            put("bid", banner.token)
            action?.let { put("action", it) }
        }

        val response = executeRequest(
            endpoint = "/api/v0/push",
            body = JSONObject().apply {
                put("context", context)
            }
        )

        if (response.code != 200) {
            throw Exception("Banner Clicked Push API error: ${response.code} - ${response.body}")
        }
    }

    /**
     * Main push method for sending tracking data
     */
    private suspend fun push(request: PushRequest): RelevaResponse = withContext(Dispatchers.IO) {
        if (!config.enableTracking) {
            return@withContext RelevaResponse(emptyList(), emptyList())
        }

        val sessionId = getSessionId()
        val wishlistProducts = storage.getWishlistData()
        val cart = storage.getCartData()

        val deviceId = storage.getDeviceId()
            ?: throw Exception("Please provide deviceId using client.setDeviceId() before using the client!")

        val context = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceIdChanged", deviceIdChanged)
            put("sessionId", sessionId)
            put("profile", JSONObject().apply {
                put("id", storage.getProfileId())
            })

            request.viewedProduct?.let { put("product", JSONObject(it.toMap())) }

            put("profileChanged", profileChanged)

            // Add page object with url, optional token, and product/category lists
            put("page", JSONObject().apply {
                request.pageUrl?.let { put("url", it) }
                request.getScreenToken()?.let { put("token", it) }
                request.pageProductIds?.let { put("ids", JSONArray(it)) }
                request.pageCategories?.let { put("categories", JSONArray(it)) }
                request.pageQuery?.let { put("query", it) }
                request.pageFilter?.let { put("filter", it.toMap()) }
            })

            // Only include cart if it exists
            cart?.let {
                val cartJson = JSONObject(it)
                put("cart", cartJson)
            }
            put("cartChanged", cartChanged)
            put("wishlist", JSONObject().apply {
                put("products", wishlistProducts?.let {
                    JSONArray(it.map { json -> JSONObject(json) })
                } ?: JSONArray())
            })
            put("wishlistChanged", wishlistChanged)
            put("mergeProfileIds", JSONArray(mergeProfileIds))

            // Add custom events if present
            request.getCustomEvents()?.let { events ->
                put("events", JSONArray(events.map { event ->
                    JSONObject(event.toMap())
                }))
            }
        }

        val requestBody = JSONObject().apply {
            put("context", context)
            put("options", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("vendor", "Releva")
                    put("platform", "android-kotlin")
                    put("version", VERSION)
                })
            })
        }

        val response = executeRequest("/api/v0/push", requestBody)

        if (response.code != 200) {
            throw Exception("Backend API error: ${response.code} - ${response.body}")
        }

        cartChanged = false
        wishlistChanged = false
        profileChanged = false
        deviceIdChanged = false
        mergeProfileIds.clear()

        RelevaResponse.fromJson(response.body)
    }

    /**
     * Track screen view
     */
    suspend fun trackScreenView(
        pageUrl: String,
        screenToken: String? = null,
        productIds: List<String>? = null,
        categories: List<String>? = null,
        filter: AbstractFilter? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        val request = PushRequest().url(pageUrl)

        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }
        productIds?.let { request.pageProductIds(it) }
        categories?.let { request.pageCategories(it) }
        filter?.let { request.pageFilter(it) }

        return push(request)
    }

    /**
     * Track screen view with custom events
     */
    suspend fun trackScreenViewWithEvents(
        pageUrl: String,
        customEvents: List<ai.releva.sdk.types.event.CustomEvent>,
        screenToken: String? = null,
        productIds: List<String>? = null,
        categories: List<String>? = null,
        filter: AbstractFilter? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        val request = PushRequest().url(pageUrl).customEvents(customEvents)

        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }
        productIds?.let { request.pageProductIds(it) }
        categories?.let { request.pageCategories(it) }
        filter?.let { request.pageFilter(it) }

        return push(request)
    }

    /**
     * Track product view
     */
    suspend fun trackProductView(
        pageUrl: String,
        productId: String,
        screenToken: String? = null,
        customFields: Map<String, Any?>? = null,
        categories: List<String>? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        val request = PushRequest().url(pageUrl)

        categories?.let { request.pageCategories(it) }
        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }

        request.productView(ViewedProduct(
            productId = productId,
            custom = customFields?.let { CustomFields.fromMap(it) } ?: CustomFields.empty()
        ))

        return push(request)
    }

    /**
     * Track search view
     */
    suspend fun trackSearchView(
        pageUrl: String,
        query: String? = null,
        screenToken: String? = null,
        resultProductIds: List<String>? = null,
        filter: AbstractFilter? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        val request = PushRequest().url(pageUrl)

        resultProductIds?.let { request.pageProductIds(it) }

        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }
        filter?.let { request.pageFilter(it) }
        query?.let { request.pageQuery(it) }


        return push(request)
    }

    /**
     * Track checkout success
     */
    suspend fun trackCheckoutSuccess(
        pageUrl: String,
        orderedCart: Cart,
        screenToken: String? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        // Temporarily set the ordered cart to ensure it's included in the push
        setCart(orderedCart)

        val request = PushRequest().url(pageUrl).cart(orderedCart)

        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }

        return push(request)
    }

    /**
     * Get or create session ID
     */
    private suspend fun getSessionId(): String = withContext(Dispatchers.IO) {
        val sessionTimestamp = storage.getSessionTimestamp()
        val savedSessionId = storage.getSessionId()

        if (savedSessionId == null ||
            sessionTimestamp == null ||
            sessionTimestamp + SESSION_DURATION_MS < System.currentTimeMillis()) {
            val newSessionId = UUID.randomUUID().toString()
            storage.setSessionId(newSessionId)
            storage.setSessionTimestamp(System.currentTimeMillis())
            newSessionId
        } else {
            savedSessionId
        }
    }

    /**
     * Get API endpoint
     */
    private fun getEndpoint(): String {
        return if (realm.isNotEmpty()) {
            "https://$realm.releva.ai"
        } else {
            "https://releva.ai"
        }
    }

    /**
     * Execute HTTP request
     */
    private fun executeRequest(endpoint: String, body: JSONObject): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("${getEndpoint()}$endpoint")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return HttpResponse(response.code, responseBody)
    }

    private data class HttpResponse(val code: Int, val body: String)
}
