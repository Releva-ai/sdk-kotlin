package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.engagement.EngagementTrackingService
import ai.releva.sdk.services.inbox.InboxApiClient
import ai.releva.sdk.services.inbox.InboxService
import ai.releva.sdk.services.nps.NpsManagerService
import ai.releva.sdk.services.session.SessionService
import ai.releva.sdk.services.storage.StorageService
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.customfield.CustomFields
import ai.releva.sdk.types.device.DeviceType
import ai.releva.sdk.types.filter.AbstractFilter
import ai.releva.sdk.types.response.BannerResponse
import ai.releva.sdk.types.response.RelevaResponse
import ai.releva.sdk.types.response.StoryResponse
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
import java.net.URLEncoder
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
) : InboxApiClient {
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

    // Track if cart/wishlist have been initialized to avoid duplicate initial pushes
    private var cartInitialized = false
    private var wishlistInitialized = false

    private var endpointOverride: String? = null

    private val npsManager = NpsManagerService()

    var engagementTrackingService: EngagementTrackingService? = null
        private set

    companion object {
        private const val TAG = "RelevaClient"
        private const val VERSION = "1.2.0-kotlin"
    }

    /**
     * Override the API endpoint URL. When set, all API calls use this URL
     * instead of the realm-based default (e.g. for local development with ngrok).
     * Pass null to clear the override and revert to the default.
     */
    fun setEndpointOverride(url: String?) {
        if (url != null && !url.startsWith("https://")) {
            Log.w(TAG, "Endpoint override uses insecure scheme — use https:// in production")
        }
        endpointOverride = url
    }

    /**
     * Set profile ID
     */
    suspend fun setProfileId(profileId: String, skipMergeWithPreviousProfileId: Boolean = false) = withContext(Dispatchers.IO) {
        val previousProfileId = storage.getProfileId()

        if (previousProfileId == null || previousProfileId != profileId) {
            profileChanged = true
            storage.setProfileId(profileId)
            if (previousProfileId != null && !skipMergeWithPreviousProfileId) {
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
     * Automatically sends a push request with updated cart and wishlist state if cart was previously initialized
     */
    suspend fun setCart(cart: Cart) {
        withContext(Dispatchers.IO) {
            val currentCart = JSONObject(cart.toMap()).toString()
            val previousCart = storage.getCartData()

            if (previousCart == null || previousCart != currentCart) {
                storage.setCartData(currentCart)
                cartChanged = true

                // Only send automatic push if cart was already initialized (not first load)
                if (cartInitialized) {
                    // Send push with current state (cart + wishlist) without page context
                    trackScreenView(
                        pageUrl = null,
                        screenToken = null,
                        productIds = null,
                        categories = null
                    )
                    Log.d(TAG, "Automatic cart state push sent")
                } else {
                    cartInitialized = true
                    Log.d(TAG, "Cart initialized (no automatic push on first load)")
                }
            }

            // Mark as initialized if cart data didn't change but not yet initialized
            if (!cartInitialized) {
                cartInitialized = true
                Log.d(TAG, "Cart initialized with existing data (no automatic push on first load)")
            }
        }
    }

    /**
     * Clear cart storage without triggering an API call
     * Use this after checkout success to prevent stale cart data from being sent on subsequent screen views
     * Resets initialization flag so next cart update is treated as initialization (no automatic push)
     * Marks cart as changed so next screen view tracks the cart state change
     */
    suspend fun clearCartStorage() {
        withContext(Dispatchers.IO) {
            storage.setCartData(JSONObject(Cart.active(emptyList()).toMap()).toString())
            cartChanged = true  // Cart state changed from paid to empty
            cartInitialized = false
            Log.d(TAG, "Cart storage cleared, marked as changed, and initialization flag reset (no API call)")
        }
    }

    /**
     * Set wishlist
     * Automatically sends a push request with updated cart and wishlist state if wishlist was previously initialized
     */
    suspend fun setWishlist(wishlistProducts: List<WishlistProduct>) {
        withContext(Dispatchers.IO) {
            val currentWishlist = wishlistProducts.map { JSONObject(it.toMap()).toString() }
            val previousWishlist = storage.getWishlistData()

            if (previousWishlist == null || currentWishlist != previousWishlist) {
                storage.setWishlistData(currentWishlist)
                wishlistChanged = true

                // Only send automatic push if wishlist was already initialized (not first load)
                if (wishlistInitialized) {
                    // Send push with current state (cart + wishlist) without page context
                    trackScreenView(
                        pageUrl = null,
                        screenToken = null,
                        productIds = null,
                        categories = null
                    )
                    Log.d(TAG, "Automatic wishlist state push sent")
                } else {
                    wishlistInitialized = true
                    Log.d(TAG, "Wishlist initialized (no automatic push on first load)")
                }
            }

            // Mark as initialized if wishlist data didn't change but not yet initialized
            if (!wishlistInitialized) {
                wishlistInitialized = true
                Log.d(TAG, "Wishlist initialized with existing data (no automatic push on first load)")
            }
        }
    }

    /**
     * Register push token
     */
    suspend fun registerPushToken(type: DeviceType, token: String) = withContext(Dispatchers.IO) {
        val deviceId = storage.getDeviceId()
            ?: throw Exception("Please provide deviceId using client.setDeviceId() before using the client!")

        val profileId = storage.getProfileId()
            ?: throw Exception("Please provide profileId using client.setProfileId() before registering push token!")

        val request = JSONObject().apply {
            put("profileId", profileId)
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
     * Track banner impression
     */
    suspend fun bannerImpression(banner: BannerResponse) = withContext(Dispatchers.IO) {
        ensureSessionTracking()
        val body = JSONObject().apply {
            put("profileId", storage.getProfileId())
            put("deviceId", storage.getDeviceId())
            put("sessionId", SessionService.getInstance().getSessionId())
            put("banners", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("token", banner.token)
                    put("bannerId", banner.bannerId.toString())
                    put("segmentId", banner.segmentId.toString())
                })
            })
        }

        val response = executeRequest(
            endpoint = "/api/v0/impressions",
            body = body
        )

        if (response.code != 200) {
            throw Exception("Banner Impression API error: ${response.code} - ${response.body}")
        }
    }

    /**
     * Track banner action (click, close, etc.)
     */
    suspend fun bannerAction(banner: BannerResponse, action: String? = null) = withContext(Dispatchers.IO) {
        ensureSessionTracking()
        val body = JSONObject().apply {
            put("deviceId", storage.getDeviceId())
            put("profileId", storage.getProfileId())
            put("sessionId", SessionService.getInstance().getSessionId())
            put("action", action)
            put("attributions", JSONObject().apply {
                put("bannerBlockId", banner.token)
                put("bannerId", banner.bannerId.toString())
            })
        }

        val response = executeRequest(
            endpoint = "/api/v0/push/events",
            body = body
        )

        if (response.code != 202) {
            throw Exception("Banner Event $action API error: ${response.code} - ${response.body}")
        }
    }

    /**
     * Track banner click (convenience alias for bannerAction)
     */
    suspend fun bannerClicked(banner: BannerResponse, action: String? = null) =
        bannerAction(banner, action)

    /**
     * Main push method for sending tracking data
     */
    private fun ensureSessionTracking() {
        SessionService.getInstance().initialize(storage, npsManager)
    }

    private suspend fun push(request: PushRequest): RelevaResponse = withContext(Dispatchers.IO) {
        if (!config.enableTracking) {
            return@withContext RelevaResponse(emptyList(), emptyList())
        }

        ensureSessionTracking()
        val sessionId = SessionService.getInstance().getSessionId()
        val wishlistProducts = storage.getWishlistData()

        // Use cart from request if explicitly set (e.g., for checkout success),
        // otherwise load from storage for regular screen views
        val cartToSend = if (request.cart != null) {
            // Cart explicitly provided in request (checkout success case)
            JSONObject(request.cart!!.toMap()).toString()
        } else {
            // Load from storage for regular tracking
            storage.getCartData()
        }

        val deviceId = storage.getDeviceId()
            ?: throw Exception("Please provide deviceId using client.setDeviceId() before using the client!")

        val payload = JSONObject().apply {
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
                request.pageFilter?.let { put("filter", JSONObject(it.toMap())) }
            })

            // Include cart if it exists (either from request or storage)
            cartToSend?.let {
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

        // Device context
        val sessionCount = storage.getDeviceSessionCount()
        val firstSeenAt = storage.getDeviceFirstSeenAt()
        val views = storage.incrementDeviceViewsCount() - 1

        payload.put("device", JSONObject().apply {
            put("sessions", sessionCount)
            put("platform", "android")
            put("views", views)
            put("sdkVersion", VERSION)
        })
        if (firstSeenAt != null) {
            payload.getJSONObject("device").put("firstSeenAt", firstSeenAt)
        }

        val requestBody = JSONObject().apply {
            put("context", payload)
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

        val relevaResponse = RelevaResponse.fromJson(response.body)

        // Initialize NPS manager with the config from this response
        npsManager.initialize(relevaResponse.nps)

        relevaResponse
    }

    // -- NPS methods --

    /**
     * Notify the SDK of a named app event. Used for:
     * - Firing NPS customEvent triggers (e.g. "checkout_complete")
     * - Cancelling pending NPS surveys via cancelOnEvents (e.g. "checkout_started")
     */
    fun trackEvent(eventName: String) {
        npsManager.trackEvent(eventName)
    }

    /**
     * Submits an NPS survey response to the server.
     * Failures are swallowed with one retry — the thank-you screen is shown
     * regardless of network outcome (per spec).
     */
    suspend fun submitNpsResponse(
        token: String,
        score: Int,
        comment: String? = null
    ) = withContext(Dispatchers.IO) {
        require(score in 0..10) { "NPS score must be 0-10" }
        ensureSessionTracking()

        val body = JSONObject().apply {
            put("profileId", storage.getProfileId())
            put("deviceId", storage.getDeviceId())
            put("sessionId", SessionService.getInstance().getSessionId())
            put("score", score)
            if (!comment.isNullOrEmpty()) put("comment", comment)
        }

        suspend fun doPost() {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val response = executeRequest("/api/v0/nps/$encodedToken/submissions", body)
            if (response.code != 202) {
                throw Exception("NPS submit error: ${response.code} - ${response.body}")
            }
        }

        try {
            doPost()
        } catch (e: Exception) {
            // One silent retry
            try {
                doPost()
            } catch (retryError: Exception) {
                Log.d(TAG, "NPS submission failed (silent): $retryError")
            }
        }
    }

    /**
     * Releases resources held by this client.
     */
    fun dispose() {
        npsManager.dispose()
        SessionService.getInstance().dispose()
    }

    // -- Story tracking methods --

    /**
     * Track story impression
     */
    suspend fun storyImpression(story: StoryResponse) {
        storyAction(story, action = "storyImpression")
    }

    /**
     * Track story action (impression, slide view, click, complete, close)
     */
    suspend fun storyAction(
        story: StoryResponse,
        action: String,
        slideId: Any? = null
    ) = withContext(Dispatchers.IO) {
        ensureSessionTracking()
        val body = JSONObject().apply {
            put("deviceId", storage.getDeviceId())
            put("profileId", storage.getProfileId())
            put("sessionId", SessionService.getInstance().getSessionId())
            put("action", action)
            put("attributions", JSONObject().apply {
                put("storyId", story.token)
                slideId?.let { put("slideId", it.toString()) }
            })
        }

        val response = executeRequest("/api/v0/push/events", body)

        if (response.code != 202) {
            throw Exception("Story Event ($action) API error: ${response.code} - ${response.body}")
        }
    }

    // -- Inbox methods --

    /**
     * Access the inbox service.
     */
    val inbox: InboxService get() = InboxService.instance

    /**
     * Initialize the inbox service. Call after setProfileId().
     */
    suspend fun initializeInbox() {
        if (!config.enableInbox) return
        InboxService.instance.initialize(client = this, storage = storage)
        InboxService.instance.refreshIfStale()
    }

    private fun getInboxUrl(endpoint: String): String {
        return "${getEndpoint()}/api/v0/inbox/$endpoint"
    }

    override suspend fun inboxFetchMessages(limit: Int, cursor: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId()
            ?: throw Exception("userId not set — call setProfileId first")

        var url = "${getInboxUrl("messages")}?userId=${URLEncoder.encode(userId, "UTF-8")}&limit=$limit"
        if (cursor != null) {
            url += "&cursor=${URLEncoder.encode(cursor, "UTF-8")}"
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.code != 200) {
            throw Exception("List messages API error: ${response.code} - $responseBody")
        }

        RelevaResponse.jsonObjectToMap(JSONObject(responseBody))
    }

    override suspend fun inboxFetchUnreadCount(): Int = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId()
            ?: throw Exception("userId not set — call setProfileId first")

        val url = "${getInboxUrl("unread-count")}?userId=${URLEncoder.encode(userId, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.code != 200) {
            throw Exception("Unread count API error: ${response.code} - $responseBody")
        }

        val data = JSONObject(responseBody)
        data.optInt("count", 0)
    }

    override suspend fun inboxMarkAsRead(messageId: String) = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId() ?: return@withContext

        val body = JSONObject().apply { put("userId", userId) }
        val response = executeRequest("/api/v0/inbox/messages/$messageId/read", body)

        if (response.code != 202) {
            throw Exception("Mark read failed: ${response.code}")
        }
    }

    override suspend fun inboxMarkAllAsRead() = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId() ?: return@withContext

        val body = JSONObject().apply { put("userId", userId) }
        val response = executeRequest("/api/v0/inbox/messages/read-all", body)

        if (response.code != 202) {
            throw Exception("Mark all read failed: ${response.code}")
        }
    }

    override suspend fun inboxDeleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId() ?: return@withContext

        val body = JSONObject().apply { put("userId", userId) }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("${getEndpoint()}/api/v0/inbox/messages/$messageId")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .delete(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code != 204) {
            throw Exception("Delete failed: ${response.code}")
        }
    }

    override suspend fun inboxTrackAction(messageId: String) = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId() ?: return@withContext

        val body = JSONObject().apply {
            put("userId", userId)
            put("devicePlatform", "android")
        }
        executeRequest("/api/v0/inbox/messages/$messageId/action", body)
        Unit
    }

    /**
     * Track screen view
     */
    suspend fun trackScreenView(
        pageUrl: String? = null,
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

        val request = PushRequest()
        pageUrl?.let { request.url(it) }

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
        pageUrl: String? = null,
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

        val request = PushRequest().customEvents(customEvents)
        pageUrl?.let { request.url(it) }

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
        pageUrl: String? = null,
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

        val request = PushRequest()
        pageUrl?.let { request.url(it) }

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
        pageUrl: String? = null,
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

        val request = PushRequest()
        pageUrl?.let { request.url(it) }

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
        pageUrl: String? = null,
        orderedCart: Cart,
        screenToken: String? = null,
        locale: String? = null,
        currency: String? = null
    ): RelevaResponse {
        if (!config.enableTracking) {
            return RelevaResponse(emptyList(), emptyList())
        }

        // Mark cart as changed since we're explicitly tracking a paid cart
        cartChanged = true

        val request = PushRequest().cart(orderedCart)
        pageUrl?.let { request.url(it) }

        screenToken?.let { request.screenToken(it) }
        locale?.let { request.locale(it) }
        currency?.let { request.currency(it) }

        val response = push(request)

        // After successfully tracking checkout with cartPaid: true,
        // clear cart storage to prevent stale cart data from being sent on subsequent screen views
        // The backend automatically clears the cart after checkout, so we just sync local state
        clearCartStorage()
        Log.d(TAG, "Cart storage automatically cleared after checkout success")

        return response
    }

    /**
     * Get API endpoint
     */
    private fun getEndpoint(): String {
        endpointOverride?.let { return it }
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
