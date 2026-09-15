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
import ai.releva.sdk.types.event.CustomEvent
import ai.releva.sdk.types.filter.AbstractFilter
import ai.releva.sdk.types.response.BannerResponse
import ai.releva.sdk.types.response.RelevaResponse
import ai.releva.sdk.types.response.StoryResponse
import ai.releva.sdk.types.tracking.*
import ai.releva.sdk.types.product.ViewedProduct
import ai.releva.sdk.types.wishlist.WishlistProduct
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    /**
     * How [execute] waits between attempts. Replaced in tests so a retry costs no real
     * seconds; nothing else has a reason to touch it.
     */
    @VisibleForTesting
    internal var retryWait: (Long) -> Unit = { Thread.sleep(it) }

    companion object {
        private const val TAG = "RelevaClient"
        private const val VERSION = "1.4.2-kotlin"

        // The Swift SDK's waits, kept identical so an outage costs both SDKs the same time:
        // a server that answered at all gets longer to recover than a network that did not.
        private const val TRANSPORT_RETRY_DELAY_MS = 1000L
        private const val SERVER_ERROR_RETRY_DELAY_MS = 2000L
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

    private fun ensureSessionTracking() {
        SessionService.getInstance().initialize(storage, npsManager)
    }

    /**
     * Send a [PushRequest] to the Releva tracking endpoint and return the response
     * (recommenders, banners, etc.). This is the primary entry point for tracking;
     * compose the request via the [PushRequest] builder (`url`, `screenToken`,
     * `productView`, `pageProductIds`, `pageCategories`, `pageQuery`, `pageFilter`,
     * `locale`, `currency`, `cart`, `customEvents`).
     *
     * Note: [PushRequest] is a mutable builder and is not thread-safe. Build a fresh
     * request per call; do not share or mutate a request instance across coroutines
     * while `push()` is running.
     */
    suspend fun push(request: PushRequest): RelevaResponse = withContext(Dispatchers.IO) {
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
                request.pageLocale?.let { put("locale", it) }
                request.pageCurrency?.let { put("currency", it) }
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
        val views = storage.incrementDeviceViewsCount()

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
     * Failures are swallowed — the thank-you screen is shown regardless of network outcome
     * (per spec). This used to also retry once itself on top of whatever `execute` did; now
     * that `execute` retries a 5xx or transport failure on its own, that second layer only
     * doubled the effective budget (and, at default settings, doubled the worst-case wait) for
     * this one endpoint. Removed so NPS submission gets exactly the policy every other request
     * gets, no more.
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

        try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val response = executeRequest("/api/v0/nps/$encodedToken/submissions", body)
            if (response.code != 202) {
                throw Exception("NPS submit error: ${response.code} - ${response.body}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "NPS submission failed (silent): $e")
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

        val response = executeGet(url, "/api/v0/inbox/messages")

        if (response.code != 200) {
            // Mirrors execute()'s own policy one function away: the body is only useful (and
            // only included) for a 5xx, capped at 500 chars. A 4xx here is exactly the class
            // of failure this API answers by echoing the offending field back — a profile
            // identifier or cart contents — and this exception message reaches both Log.e
            // and the public InboxState.lastRefreshError, so it must not carry that.
            val detail = if (response.code >= 500) " - ${response.body.take(500)}" else ""
            throw Exception("List messages API error: ${response.code}$detail")
        }

        RelevaResponse.jsonObjectToMap(JSONObject(response.body))
    }

    override suspend fun inboxFetchUnreadCount(): Int = withContext(Dispatchers.IO) {
        val userId = storage.getProfileId()
            ?: throw Exception("userId not set — call setProfileId first")

        val url = "${getInboxUrl("unread-count")}?userId=${URLEncoder.encode(userId, "UTF-8")}"

        val response = executeGet(url, "/api/v0/inbox/unread-count")

        if (response.code != 200) {
            // See inboxFetchMessages above: body only for a 5xx, capped at 500 chars.
            val detail = if (response.code >= 500) " - ${response.body.take(500)}" else ""
            throw Exception("Unread count API error: ${response.code}$detail")
        }

        JSONObject(response.body).optInt("count", 0)
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

        val response = execute(
            Request.Builder()
                .url("${getEndpoint()}/api/v0/inbox/messages/$messageId")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .delete(requestBody)
                .build(),
            "DELETE", "/api/v0/inbox/messages/:id"
        )
        if (response.code != 204) {
            // See inboxFetchMessages above: body only for a 5xx, capped at 500 chars.
            val detail = if (response.code >= 500) " - ${response.body.take(500)}" else ""
            throw Exception("Delete failed: ${response.code}$detail")
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
        customEvents: List<CustomEvent>,
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
     * Track a single custom event. Convenience around `push(PushRequest().customEvents(...))`.
     *
     * Returns a [RelevaResponse] just like [push] — callers may inspect
     * `response.recommenders` and `response.banners` to render any recommendations
     * the server returns alongside the event.
     */
    suspend fun trackCustomEvent(
        event: CustomEvent,
        pageUrl: String? = null,
        screenToken: String? = null
    ): RelevaResponse {
        val request = PushRequest().customEvents(listOf(event))
        pageUrl?.let { request.url(it) }
        screenToken?.let { request.screenToken(it) }

        return push(request)
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
     * Sends a request and logs it. Every call `RelevaClient` itself makes goes through here —
     * `NavigationService`, `EngagementTrackingService` and `NotificationTrampolineActivity`
     * call `httpClient.newCall(...)` directly and are out of scope for this fix; they already
     * log (in their own formats), and folding them into this path is a separate, larger change
     * than the inbox gap this was written to close.
     *
     * This is one function rather than a rule each verb follows because it was a rule each
     * verb followed: POST logged, and the inbox's own GETs and DELETE — added later and
     * calling httpClient directly — did not. That is the worst place for the gap, because the
     * list and unread-count endpoints answer an authorisation failure with an error the
     * service layer swallows, so a 401 and a genuinely empty inbox look identical in the app.
     * With nothing in the log, there is nothing on the device that tells them apart.
     *
     * [label] is the endpoint path only, never the full URL: a GET carries the profile id in
     * its query string, and identifiers stay out of the log for the same reason request
     * bodies do.
     *
     * Success is any 2xx, not 200: token registration answers 202 and delete answers 204, and
     * both were being logged as warnings for succeeding. Whether a given endpoint's particular
     * 2xx is the one the caller wanted is the caller's business — this decides only whether
     * the line reads as a failure.
     *
     * The response body is only ever logged for a 5xx, and capped at 500 chars even then.
     * A 4xx gets status and timing only: several of this API's validation errors echo the
     * offending field value, which for this SDK is a profile identifier or cart contents, and
     * a 4xx is exactly the class of failure a bad or replayed request produces. [RelevaConfig.enableRequestLogging]
     * gates all of this so an integrator can silence it in their own release builds.
     *
     * A failure that says nothing about the request — it never reached the server, or the
     * server answered 5xx — is retried up to [RelevaConfig.maxRetryAttempts] attempts in
     * total, matching the Swift SDK's policy and its waits. A 4xx and any 2xx are returned
     * to the caller on the spot: a 4xx is the server's verdict on this request, and repeating
     * it would only produce the same verdict. Once the attempts are spent the last failure
     * reaches the caller exactly as it did before, response or exception.
     *
     * The wait happens on the calling thread. Every caller of this method is already inside
     * `withContext(Dispatchers.IO)` and blocked on the call itself, so there is no main
     * thread to block, and sleeping there costs one pooled IO thread for a second or two
     * rather than turning this and `executeGet`/`executeRequest` into suspending functions.
     */
    private fun execute(request: Request, verb: String, label: String): HttpResponse {
        val maxAttempts = config.maxRetryAttempts.coerceAtLeast(1)
        var attempt = 1
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val response: Response
            val responseBody: String
            try {
                response = httpClient.newCall(request).execute()
                // .use{} so a body read that fails partway (a read timeout mid-transfer)
                // still closes the response instead of leaking the connection — a leak this
                // loop could now repeat up to maxAttempts times for one logical request.
                responseBody = response.use { it.body?.string() ?: "" }
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                retryAfter(TRANSPORT_RETRY_DELAY_MS, verb, label, attempt, maxAttempts)
                attempt++
                continue
            }
            val tookMs = SystemClock.elapsedRealtime() - started

            if (config.enableRequestLogging) {
                when {
                    response.isSuccessful ->
                        Log.d(TAG, "$verb $label -> ${response.code} in ${tookMs}ms (${responseBody.length} bytes)")
                    response.code >= 500 ->
                        Log.w(TAG, "$verb $label -> ${response.code} in ${tookMs}ms: ${responseBody.take(500)}")
                    else ->
                        Log.w(TAG, "$verb $label -> ${response.code} in ${tookMs}ms")
                }
            }

            if (response.code in 500..599 && attempt < maxAttempts) {
                retryAfter(SERVER_ERROR_RETRY_DELAY_MS, verb, label, attempt, maxAttempts)
                attempt++
                continue
            }

            return HttpResponse(response.code, responseBody)
        }
    }

    /**
     * Logs the attempt that just failed and takes the wait before the next one. One function
     * for both retry paths so the line keeps the shape the rest of the request log has.
     */
    private fun retryAfter(delayMs: Long, verb: String, label: String, attempt: Int, maxAttempts: Int) {
        if (config.enableRequestLogging) {
            Log.w(TAG, "$verb $label -> attempt $attempt of $maxAttempts failed, retrying in ${delayMs}ms")
        }
        retryWait(delayMs)
    }

    private fun executeGet(url: String, label: String): HttpResponse =
        execute(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build(),
            "GET", label
        )

    private fun executeRequest(endpoint: String, body: JSONObject): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)

        // Request bodies are deliberately never logged: they carry profile identifiers
        // and cart contents.
        return execute(
            Request.Builder()
                .url("${getEndpoint()}$endpoint")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build(),
            "POST", endpoint
        )
    }

    private data class HttpResponse(val code: Int, val body: String)
}
