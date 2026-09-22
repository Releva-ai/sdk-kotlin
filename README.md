# Releva SDK for Android (Kotlin)

Native Android SDK for the Releva marketing platform, providing e-commerce tracking, personalization, and analytics.

## Features

- **E-commerce Personalization** - AI-powered product recommendations and dynamic content
- **Mobile Tracking & Analytics** - Screen tracking, product views, search, checkout events
- **Push Notifications** - FCM integration with engagement tracking
- **In-App Banners** - Dynamic banners with trigger logic and native rendering
- **Stories** - Instagram/Facebook-style full-screen story viewer with auto-advance and slide tracking
- **NPS Surveys** - Built-in NPS survey UI with trigger evaluation, follow-up questions, and submission
- **App Inbox** - Persistent inbox with pagination, optimistic updates, lifecycle-aware refresh, and silent push sync
- **Flexible Configuration** - Modular setup for tracking-only, messaging-only, or full-featured modes

## Requirements

- Android API 24+ (Android 7.0 Nougat)
- Kotlin 2.0+
- AndroidX libraries
- Kotlin Coroutines

## Quick Start

### Installation

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

Add SDK dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Releva-ai:sdk-kotlin:1.5.1")
}
```

### Initialize

```kotlin
import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.config.RelevaConfig

class MyApplication : Application() {
    lateinit var relevaClient: RelevaClient

    override fun onCreate() {
        super.onCreate()

        relevaClient = RelevaClient(
            context = applicationContext,
            realm = "",
            accessToken = "your-access-token",
            config = RelevaConfig.full()
        )

        lifecycleScope.launch {
            relevaClient.setDeviceId("unique-device-id")
            relevaClient.setProfileId("user-profile-id")
        }
    }
}
```

### Track Events

Most tracking — screen views, product views, search, checkout, recommendations — is sent through a single API: build a `PushRequest` using the fluent builder, then hand it to `client.push(...)`. Custom events are the exception and use `client.trackCustomEvent(...)` directly.

```kotlin
import ai.releva.sdk.types.tracking.PushRequest
import ai.releva.sdk.types.product.ViewedProduct
import ai.releva.sdk.types.customfield.CustomFields

lifecycleScope.launch {
    val request = PushRequest()
        .url("myapp://product/details")
        // Use the actual token from Releva admin (UUID) once configured
        // .screenToken("abc123def456")
        .productView(ViewedProduct(productId = "product-123", custom = CustomFields.empty()))

    val response = relevaClient.push(request)

    if (response.hasRecommenders) {
        // Render recommendations in your UI
    }
}
```

## Documentation

- **[Integration Guide](INTEGRATION_GUIDE.md)** - Complete step-by-step integration guide for app developers
- **[Development Guide](DEVELOPMENT.md)** - SDK architecture and contribution guide for maintainers
- **[Releasing Guide](RELEASING.md)** - How to release new versions of the SDK

## Key Concepts

**Screen Tokens**: UUID-format tokens from your Releva admin account (not human-readable strings)

**Page URLs**: Full URLs using custom schemes (`myapp://product/details`) or HTTPS

**No UI Components**: SDK provides data APIs only - your app must render recommendations

## Core Functionality

### E-commerce Tracking
- Track product views, searches, checkout
- Advanced filtering with nested AND/OR logic
- Custom events with products and tags

### Cart & Wishlist
- Automatic state detection
- Cart automatically clears after checkout
- Wishlist management

### Push Notifications
- Firebase Cloud Messaging integration
- Engagement tracking (delivered, opened, dismissed)
- Automatic navigation handling

### NPS Surveys
- Server-side trigger evaluation (appOpen, sessionCount, screenView)
- Client-side custom event triggers via `trackEvent(eventName)`
- Cancel events to suppress survey for the session
- Configurable delay before display
- Built-in bottom sheet/modal UI with score selection, follow-up questions, and thank-you screen
- Dark mode support
- Submission fails silently on the client (the thank-you screen shows regardless); transport and 5xx failures get the SDK's standard retry, same as every other request

### Stories
- Full-screen story viewer (`StoryViewerActivity`) with progress bars and tap navigation (left half = previous, right half = next)
- Auto-advance per slide with configurable duration
- End behaviors: dismiss, loop, stayOnLast
- Trigger types: immediately, delaySeconds, scrollPercentage, cartChanged, wishlistChanged
- Slide rendering via `DesignRenderer` (same as banners)
- Interactive elements (buttons, links) inside slides receive taps correctly — non-interactive areas trigger slide navigation
- Tracking: impression, slide view, slide click, complete, close

### In-App Banners & Design Rendering
- Popup, bar, flyout, and static banners with Unlayer design rendering
- Supported content types: image, text, heading, button, menu, carousel, divider
- **Carousel**: swipe and tap navigation, directional slide animations, autoplay with optional loop, dot indicators or image preview strip
- `onLinkTap` callback is **required** — apps must handle link/button navigation

### App Inbox
- Cursor-based pagination (fetch first page, load more)
- Optimistic updates with automatic rollback on failure
- Stale cache refresh (> 5 min threshold)
- Lifecycle-aware: refreshes on app resume if stale
- Silent push sync via `data["inbox_sync"] == "true"` flag
- Message lookup by `inboxMessageId` for push-to-inbox navigation
- Persistent cache via SharedPreferences
- Mutation methods (`markAsRead`, `deleteMessage`, etc.) survive fragment lifecycle cancellation

### Endpoint Override
```kotlin
// For local development with ngrok
client.setEndpointOverride("https://your-ngrok-url.ngrok-free.app")
// Clear override to revert to default
client.setEndpointOverride(null)
```

### Configuration Options
```kotlin
RelevaConfig.full()          // All features
RelevaConfig.trackingOnly()  // Analytics only
RelevaConfig.pushOnly()      // Push notifications only
```

## Navigation Setup

The SDK provides navigation hooks for push notifications, banners, and stories. Your app is responsible for mapping navigation targets to actual screen transitions.

### 1. Push Notification Navigation

Push notifications use a three-component flow: FCM service → trampoline activity → your navigation handler.

#### a. Implement `NavigationHandler`

```kotlin
import ai.releva.sdk.services.navigation.NavigationHandler

class AppNavigationHandler(private val navController: NavController) : NavigationHandler {

    override fun getScreenMappings(): Map<String, Any> = mapOf(
        "home" to R.id.homeFragment,
        "cart" to R.id.cartFragment,
        "account" to R.id.accountFragment,
        "orders" to R.id.ordersFragment,
        "product_details" to R.id.productDetailsFragment,
        "inbox" to R.id.inboxFragment
    )

    override fun navigateToScreen(screenName: String, parameters: Bundle) {
        val destinationId = getScreenMappings()[screenName] as? Int ?: return
        navController.navigate(destinationId, parameters)
    }
}
```

#### b. Extend `RelevaFirebaseMessagingService`

```kotlin
class MyFirebaseMessagingService : RelevaFirebaseMessagingService() {
    override fun getMainActivityClass(): Class<*> = MainActivity::class.java
    override fun getNotificationIcon(): Int = R.drawable.ic_notification
    override fun getDefaultNotificationTitle(): String = "My App"
    override fun onPushTokenGenerated(token: String) {
        // Forward to your RelevaClient instance
        relevaClient.setPushToken(token)
    }
}
```

Register in `AndroidManifest.xml`:
```xml
<service android:name=".MyFirebaseMessagingService" android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

#### c. Wire up in `MainActivity`

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navController = findNavController(R.id.nav_host_fragment)
        NavigationService.getInstance().setNavigationHandler(AppNavigationHandler(navController))
        NavigationService.getInstance().handleNotificationNavigation(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        NavigationService.getInstance().handleNotificationNavigation(this, intent)
    }
}
```

The SDK's `NotificationTrampolineActivity` handles notification dismissal, callback tracking, and routing (to URL, screen, or inbox) before forwarding to your `MainActivity`.

### 2. Banner Link Taps

`BannerDisplayManager` requires an `onLinkTap` callback that receives the tapped URL:

```kotlin
val bannerManager = BannerDisplayManager(
    client = relevaClient,
    targetSelector = "#home-content",
    onLinkTap = { url ->
        // Handle navigation — e.g. parse deep links or open browser
        val uri = Uri.parse(url)
        if (uri.scheme == "myapp") {
            when (uri.host) {
                "cart" -> navController.navigate(R.id.cartFragment)
                "product" -> { /* navigate with product ID */ }
            }
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
)
bannerManager.attach(fragment) // or attach(activity)
```

### 3. Story Link Taps

`StoryDisplayManager` uses a similar `onLinkTap` callback for interactive elements (buttons, links) inside story slides:

```kotlin
StoryDisplayManager.setClient(relevaClient)
StoryDisplayManager.setOnLinkTap { url ->
    val uri = Uri.parse(url)
    if (uri.scheme in listOf("http", "https")) {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
StoryDisplayManager.attach(activity)
```

`setOnLinkTap` must be called before `attach()`.

### 4. Story Trigger Evaluation

`StoryDisplayManager` only shows a story once something asks it to (via `StoryDisplayController.showStory`); it does not itself decide *when* a story with a given `trigger` (`immediately`, `delaySeconds`, `scrollPercentage`, `cartChanged`, `wishlistChanged`) should fire. `StoryManagerService` evaluates that, but the SDK does not construct or drive one for you — wire it up yourself against `RelevaResponse.stories` from a push response:

```kotlin
val storyManager = StoryManagerService()
storyManager.initialize(relevaResponse.stories)

// Call these from wherever your app already tracks the corresponding state change:
storyManager.onCartChanged()
storyManager.onWishlistChanged()
storyManager.onScrollPercentageReached(percentage)
```

`immediately` and `delaySeconds` triggers fire on their own once `initialize()` is called; the other three need the matching method called from your own cart/wishlist/scroll code. `leaveIntent` is not supported on mobile. Call `storyManager.dispose()` when you are done with it to cancel any pending `delaySeconds` timers.

## Technology Stack

- **Storage**: SharedPreferences
- **Async**: Kotlin Coroutines
- **HTTP**: OkHttp
- **Platform**: Android only
- **Build**: Gradle

## Distribution

This SDK is distributed via [JitPack](https://jitpack.io/#Releva-ai/sdk-kotlin). View all versions at https://jitpack.io/#Releva-ai/sdk-kotlin

## Data safety (Google Play)

Play's Data safety form is declared per app in Play Console; nothing shipped in the AAR feeds it, and Apple's `PrivacyInfo.xcprivacy` has no Android equivalent. This section exists so you can transcribe what this SDK sends on your behalf into your own form. It covers this SDK only — your app and every other SDK in it are still yours to declare.

### What the SDK collects

| Play data type | What it is |
| --- | --- |
| Personal info → User IDs | the `profileId` you set with `setProfileId(...)` |
| Device or other IDs | the `deviceId` you set with `setDeviceId(...)`, the FCM push token passed to `registerPushToken(...)`, and the SDK's own session id (a fresh UUID per session) |
| App activity → App interactions | screen and product views, cart and wishlist contents, custom events, banner and story impressions and clicks, inbox reads/deletions/taps, notification taps, the NPS score from `submitNpsResponse(...)`, and the per-device session and view counters |
| App activity → In-app search history | the `query` passed to `trackSearchView(...)`, sent as `page.query` |
| Financial info → Purchase history | the order id, product ids, quantities and prices of the `Cart` passed to `trackCheckoutSuccess(...)` |
| App activity → Other user-generated content | the optional free-text comment on `submitNpsResponse(...)` |

**Purposes**: for everything except the NPS comment, declare **App functionality, Personalization** and **Analytics**, plus Advertising or marketing — see below. For the NPS comment, **App functionality only**: it is read individually by a marketer rather than aggregated into an audience measure.

**Linked to an identity**: every request the SDK composes carries the `profileId`, and the tracking payload carries the `deviceId` alongside it — so declare all of the above linked to a user, not anonymous. (Two narrower cases: the inbox requests send only the `profileId`, and notification-tap tracking sends neither id, just the push payload's own callback URL.)

**Not collected**: the SDK has no profile-attribute API. No request it builds carries a name, email address, phone number or postal address field; a user is identified solely by the `profileId` you set. Anything beyond the table reaches Releva only if you put it in a custom field or custom event yourself.

### Advertising or marketing

Play's "Advertising or marketing" purpose covers displaying or targeting advertising *or marketing communications*. Push campaigns and personalised banners are marketing communications, so expect to tick it for the behavioural types above. Confirm it against how you actually use Releva rather than taking it as settled here.

### The advertising ID

This SDK adds no advertising-ID permission:

- `releva-sdk/src/main/AndroidManifest.xml` declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`, and no other permission. (It also merges a broadcast receiver and two activities into your app for notification handling and the story viewer — harmless for this section, but you will see them if you inspect your merged manifest.)
- `firebase-messaging` is `compileOnly` in `releva-sdk/build.gradle.kts`, so the SDK pulls no Firebase artifact into your build at all.

Firebase Analytics does, though, and most apps that set up FCM also add it. Adding `com.google.firebase:firebase-analytics` merges two more permissions into your release manifest:

```
app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
    <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
    <uses-permission android:name="android.permission.ACCESS_ADSERVICES_AD_ID" />
```

Both come from `com.google.android.gms:play-services-measurement-api`, which `firebase-analytics` pulls in — your manifest merger report will name it.

This matters because Play cross-checks the Data safety answers against the permissions in the uploaded artifact: declaring that the app collects no advertising ID while shipping `AD_ID` gets the release flagged. Check your own merged manifest at `app/build/intermediates/merged_manifests/<variant>/.../AndroidManifest.xml`. If you do not use the advertising ID, remove it rather than declaring it — with `xmlns:tools="http://schemas.android.com/tools"` on your `<manifest>` tag:

```xml
<uses-permission
    android:name="com.google.android.gms.permission.AD_ID"
    tools:node="remove" />
```

### Collected vs shared

Data the SDK sends to Releva's endpoint is **collection**. Play does not treat a transfer to a service provider processing on the developer's behalf as **sharing**. Whether a given Releva account then forwards conversions on to advertising networks is configuration on Releva's infrastructure, not a call site in this SDK — so the answer can change with no release here. Confirm it for your integration, and re-confirm it at each submission.

### Encryption in transit, and deletion

- Every request the SDK builds goes to `https://<realm>.releva.ai`, or `https://releva.ai` when the realm is empty, so it is encrypted in transit. A few things can change that, all server-controlled rather than SDK-controlled: `setEndpointOverride(...)` only logs a warning for a non-`https://` URL, it does not refuse it; notification-tap tracking fetches the callback URL carried in the push payload; and banner/story images and a notification's big-picture image are fetched from URLs the server supplies. None of these carry collected data upward — they only affect what scheme the SDK ends up fetching from.
- The SDK exposes no delete, erase or forget call. `clearCartStorage()` clears local cart state and `inbox.deleteMessage(...)` removes a single inbox message; neither is a data-deletion request. If you answer that users can request deletion of their data, that route has to exist on Releva's side — it is not an in-app button.

### What you still have to do yourself

1. **The Play Console form itself.** Play derives it from nothing in this repository.
2. **Your custom fields and custom events.** `trackCustomEvent(...)`, and the `custom` maps on viewed, cart and wishlist products, send whatever you put in them. The SDK cannot describe data it does not choose; anything sensitive passed through one is yours to declare.
3. **Firebase.** Push goes through `firebase-messaging`, which you add to your own build and which carries its own disclosures.
4. **Every other SDK in your app.**

## Support

- **Website**: https://releva.ai
- **Email**: tech-support@releva.ai

## License

Copyright © Releva.ai
