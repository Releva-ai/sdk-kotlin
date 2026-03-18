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
    implementation("com.github.Releva-ai:sdk-kotlin:1.1.1")
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

```kotlin
lifecycleScope.launch {
    val response = relevaClient.trackProductView(
        pageUrl = "myapp://product/details",
        screenToken = null,
        productId = "product-123"
    )

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
- Submission with one silent retry

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

## Example Projects

See the `shopping-android-app` directory for a complete integration example.

## Technology Stack

- **Storage**: SharedPreferences
- **Async**: Kotlin Coroutines
- **HTTP**: OkHttp
- **Platform**: Android only
- **Build**: Gradle

## Distribution

This SDK is distributed via [JitPack](https://jitpack.io/#Releva-ai/sdk-kotlin). View all versions at https://jitpack.io/#Releva-ai/sdk-kotlin

## Support

- **Website**: https://releva.ai
- **Email**: tech-support@releva.ai

## License

Copyright © Releva.ai
