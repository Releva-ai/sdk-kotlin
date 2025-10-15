# Releva SDK for Android (Kotlin)

This is the native Android SDK for the Releva marketing platform, translated from the Flutter SDK. It targets Android devices and provides all the core functionality for e-commerce tracking, personalization, and analytics.

## Features

### 🎯 **E-commerce Personalization**
- **Product Recommendations**: AI-powered product suggestions with real-time personalization
- **Dynamic Content**: Personalized banners and content blocks based on user behavior
- **Advanced Filtering**: Complex product filtering with nested AND/OR logic, price ranges, custom fields
- **Smart Search**: Search tracking with result optimization and recommendation integration

### 📱 **Mobile Tracking & Analytics**
- **Screen Tracking**: Track user navigation through your app
- **E-commerce Events**: Product views, cart changes, checkout tracking, search analytics
- **Real-Time Analytics**: ClickHouse integration for comprehensive engagement insights

### 🔔 **Push Notifications**
- **Firebase Integration**: FCM push notification engagement tracking with offline support
- **Engagement Analytics**: Delivered, opened, dismissed tracking to ClickHouse
- **Cross-Platform**: Android device support

### ⚙️ **Flexible Configuration**
- **Modular Setup**: Enable only needed features (tracking-only, messaging-only, full-featured)
- **Use Case Optimized**: Perfect for apps with existing analytics or standalone messaging needs
- **Production Ready**: Robust error handling, offline support, automatic retries

## Requirements

- Android API 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+
- AndroidX libraries
- Kotlin Coroutines

## Installation

### Using Gradle

Add the SDK to your `build.gradle.kts` or `build.gradle`:

```kotlin
dependencies {
    implementation("ai.releva:sdk:0.0.1")
}
```

Or include the module directly in your project:

1. Copy the `releva-sdk` folder into your project
2. Add to your `settings.gradle.kts`:
```kotlin
include(":releva-sdk")
```

3. Add to your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":releva-sdk"))
}
```

### Permissions

The SDK automatically includes these permissions in its manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quick Start

### Initialize the SDK

```kotlin
import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.config.RelevaConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MyApplication : Application() {
    lateinit var relevaClient: RelevaClient
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Initialize with configuration
        relevaClient = RelevaClient(
            context = applicationContext,
            realm = "",                    // Usually empty string
            accessToken = "your-access-token", // Provided by Releva
            config = RelevaConfig.full()   // or .messagingOnly(), .trackingOnly()
        )

        // Set required identifiers
        scope.launch {
            relevaClient.setDeviceId("unique-device-id")
            relevaClient.setProfileId("user-profile-id")

            // Enable push engagement tracking (optional)
            relevaClient.enablePushEngagementTracking()
        }
    }
}
```

## Usage Examples

### E-commerce Tracking

#### Track Product View

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ProductDetailActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scope.launch {
            val response = relevaClient.trackProductView(
                screenToken = "product_detail",
                productId = "product-123",
                categories = listOf("electronics", "phones")
            )

            // Handle personalized recommendations
            if (response.hasRecommenders) {
                response.recommenders.forEach { recommender ->
                    Log.d("Releva", "${recommender.name}: ${recommender.response.size} products")
                    // Display recommendations in your UI
                }
            }
        }
    }
}
```

#### Track Search

```kotlin
import ai.releva.sdk.types.filter.SimpleFilter
import ai.releva.sdk.types.filter.NestedFilter

scope.launch {
    val response = relevaClient.trackSearchView(
        screenToken = "search_results",
        query = "red running shoes",
        filter = NestedFilter.and(listOf(
            SimpleFilter.priceRange(minPrice = 50.0, maxPrice = 200.0),
            SimpleFilter.brand(brand = "Nike"),
            SimpleFilter.color(color = "red")
        ))
    )

    // Handle search results and recommendations
    displaySearchResults(response)
}
```

#### Track Checkout Success

```kotlin
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.customfield.CustomFields

scope.launch {
    val cart = Cart.active(listOf(
        CartProduct(
            id = "product-123",
            price = 99.99,
            quantity = 2.0,
            custom = CustomFields.empty()
        )
    ))

    val response = relevaClient.trackCheckoutSuccess(
        screenToken = "checkout_success",
        orderedCart = cart,
        userEmail = "user@example.com",
        userPhoneNumber = "+1234567890"
    )
}
```

### Cart and Wishlist Management

```kotlin
import ai.releva.sdk.types.cart.Cart
import ai.releva.sdk.types.cart.CartProduct
import ai.releva.sdk.types.wishlist.WishlistProduct

// Update cart
scope.launch {
    val cart = Cart.active(listOf(
        CartProduct(
            id = "product-123",
            price = 99.99,
            quantity = 1.0
        ),
        CartProduct(
            id = "product-456",
            price = 49.99,
            quantity = 2.0
        )
    ))

    relevaClient.setCart(cart)
}

// Update wishlist
scope.launch {
    val wishlist = listOf(
        WishlistProduct(id = "product-789"),
        WishlistProduct(id = "product-101")
    )

    relevaClient.setWishlist(wishlist)
}
```

### Advanced Filtering

```kotlin
import ai.releva.sdk.types.filter.SimpleFilter
import ai.releva.sdk.types.filter.NestedFilter
import ai.releva.sdk.types.filter.FilterAction

// Complex product filtering
val complexFilter = NestedFilter.and(listOf(
    // Price range
    SimpleFilter.priceRange(minPrice = 10.0, maxPrice = 100.0),

    // Multiple brands (OR condition)
    NestedFilter.or(listOf(
        SimpleFilter.brand(brand = "Nike"),
        SimpleFilter.brand(brand = "Adidas")
    )),

    // Size and color combinations
    NestedFilter.and(listOf(
        NestedFilter.or(listOf(
            SimpleFilter.size(size = "42"),
            SimpleFilter.size(size = "43")
        )),
        SimpleFilter.color(color = "red")
    ))
))

scope.launch {
    val response = relevaClient.trackSearchView(
        screenToken = "filtered_products",
        filter = complexFilter
    )
}
```

### Push Notification Tracking

#### Register Firebase Token

```kotlin
import com.google.firebase.messaging.FirebaseMessaging
import ai.releva.sdk.types.device.DeviceType

FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (task.isSuccessful) {
        val token = task.result
        scope.launch {
            relevaClient.registerPushToken(DeviceType.ANDROID, token)
        }
    }
}
```

#### Handle Push Notification Engagement

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class MyFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val engagementService = (application as MyApplication).relevaClient.engagementTrackingService

        if (engagementService?.isRelevaMessage(message.data) == true) {
            scope.launch {
                engagementService.trackEngagement(message.data)
            }
        }
    }
}
```

### Configuration Options

```kotlin
// Full e-commerce functionality (default)
val fullConfig = RelevaConfig.full()

// Only tracking (analytics without messaging)
val trackingConfig = RelevaConfig.trackingOnly()

// Custom configuration
val customConfig = RelevaConfig(
    enableTracking = true,
    enableScreenTracking = true,
    enableInAppMessaging = false,
    enablePushNotifications = true,
    enableAnalytics = true
)

val client = RelevaClient(
    context = context,
    realm = "",
    accessToken = "your-token",
    config = customConfig
)
```

## Architecture

### Core Components

```
ai.releva.sdk/
├── client/
│   └── RelevaClient.kt              # Main SDK client
├── config/
│   └── RelevaConfig.kt              # Configuration management
├── types/
│   ├── cart/                        # Cart and product models
│   ├── filter/                      # Advanced filtering system
│   ├── response/                    # API response models
│   ├── tracking/                    # Tracking request models
│   ├── wishlist/                    # Wishlist models
│   ├── device/                      # Device type enums
│   └── customfield/                 # Custom field support
└── services/
    ├── storage/                     # Local storage service
    └── engagement/                  # Engagement tracking service
```

### Key Classes

- **RelevaClient**: Main entry point for all SDK functionality
- **RelevaConfig**: Configuration for enabling/disabling features
- **StorageService**: Persistent storage using SharedPreferences
- **EngagementTrackingService**: Push notification engagement tracking
- **RelevaResponse**: Structured API responses with recommenders and banners

## Differences from Flutter SDK

This Kotlin SDK provides the same core functionality as the Flutter SDK with these platform-specific differences:

1. **Storage**: Uses SharedPreferences instead of Hive
2. **Coroutines**: Uses Kotlin Coroutines instead of Dart async/await
3. **HTTP**: Uses OkHttp instead of Dart's http package
4. **No UI Components**: Focuses on data and tracking APIs only (no in-app messaging UI)
5. **Android-Specific**: Optimized for native Android development

## ProGuard Configuration

The SDK includes ProGuard rules. If you use custom ProGuard rules, ensure these are included:

```proguard
-keep public class ai.releva.sdk.** { public *; }
-keepclassmembers class ai.releva.sdk.types.** { *; }
```

## Threading and Coroutines

All SDK methods that perform network operations are `suspend` functions and must be called from a coroutine scope:

```kotlin
// In Activity/Fragment
lifecycleScope.launch {
    relevaClient.trackProductView(...)
}

// In ViewModel
viewModelScope.launch {
    relevaClient.trackSearchView(...)
}

// Custom scope
val scope = CoroutineScope(Dispatchers.IO)
scope.launch {
    relevaClient.setCart(cart)
}
```

## Error Handling

```kotlin
scope.launch {
    try {
        val response = relevaClient.trackProductView(
            screenToken = "product_detail",
            productId = "123"
        )
        // Handle success
    } catch (e: Exception) {
        Log.e("Releva", "Error tracking product view", e)
        // Handle error
    }
}
```

## Version

Current version: **0.0.1-kotlin**

Based on Flutter SDK version: **0.0.34**

## Additional Information

For additional information please visit [https://releva.ai](https://releva.ai) and/or reach out to tech-support@releva.ai

---

# SDK Development Guide

This section is for developers maintaining and extending the Releva SDK.

## Project Structure

### Directory Structure

```
sdk-kotlin/
├── releva-sdk/                                    # Main SDK module
│   ├── src/
│   │   └── main/
│   │       ├── kotlin/ai/releva/sdk/
│   │       │   ├── client/
│   │       │   │   └── RelevaClient.kt           # Main SDK client class
│   │       │   ├── config/
│   │       │   │   └── RelevaConfig.kt           # SDK configuration
│   │       │   ├── types/
│   │       │   │   ├── cart/
│   │       │   │   │   ├── Cart.kt               # Shopping cart model
│   │       │   │   │   └── CartProduct.kt        # Cart product model
│   │       │   │   ├── customfield/
│   │       │   │   │   └── CustomFields.kt       # Custom fields support
│   │       │   │   ├── device/
│   │       │   │   │   └── DeviceType.kt         # Device type enum
│   │       │   │   ├── filter/
│   │       │   │   │   ├── AbstractFilter.kt     # Filter interface
│   │       │   │   │   ├── SimpleFilter.kt       # Simple filter implementation
│   │       │   │   │   └── NestedFilter.kt       # Nested filter (AND/OR logic)
│   │       │   │   ├── response/
│   │       │   │   │   ├── RelevaResponse.kt     # Main API response
│   │       │   │   │   ├── RecommenderResponse.kt # Product recommendations
│   │       │   │   │   └── BannerResponse.kt     # Banner response
│   │       │   │   ├── tracking/
│   │       │   │   │   └── PushRequest.kt        # Tracking request models
│   │       │   │   └── wishlist/
│   │       │   │       └── WishlistProduct.kt    # Wishlist product model
│   │       │   └── services/
│   │       │       ├── engagement/
│   │       │       │   └── EngagementTrackingService.kt # Push tracking
│   │       │       ├── storage/
│   │       │       │   └── StorageService.kt     # Local storage
│   │       │       └── navigation/
│   │       │           ├── NavigationService.kt   # Navigation orchestration
│   │       │           ├── NavigationHandler.kt   # Navigation interface
│   │       │           └── RelevaFirebaseMessagingService.kt # FCM service base
│   │       ├── res/                              # Android resources
│   │       └── AndroidManifest.xml               # SDK manifest
│   ├── build.gradle.kts                          # SDK build configuration
│   ├── proguard-rules.pro                        # ProGuard rules
│   └── consumer-rules.pro                        # Consumer ProGuard rules
├── build.gradle.kts                              # Root build configuration
├── settings.gradle.kts                           # Gradle settings
├── gradle.properties                             # Gradle properties
├── .gitignore                                    # Git ignore rules
├── README.md                                     # This file
└── INTEGRATION_GUIDE.md                          # Detailed integration guide
```

### Core Components

#### 1. RelevaClient (`client/RelevaClient.kt`)

The main entry point for all SDK functionality. Handles:
- User identification (device ID, profile ID)
- E-commerce tracking (product views, searches, checkout)
- Cart and wishlist management with automatic state detection
- API communication
- Session management

**Key Methods:**
- `setDeviceId()` - Set unique device identifier
- `setProfileId()` - Set user profile identifier
- `trackProductView()` - Track product page views
- `trackSearchView()` - Track search queries
- `trackCheckoutSuccess()` - Track completed orders (automatically clears cart)
- `setCart()` - Update shopping cart
- `setWishlist()` - Update wishlist
- `clearCartStorage()` - Clear cart without API call
- `registerPushToken()` - Register FCM token
- `bannerClicked()` - Track banner interactions

**State Management:**
- `cartInitialized` - Flag to track if cart has been loaded
- `wishlistInitialized` - Flag to track if wishlist has been loaded
- `cartChanged` - Flag indicating cart state has changed
- `wishlistChanged` - Flag indicating wishlist state has changed

**Important Implementation Details:**
- After `trackCheckoutSuccess()`, the SDK automatically calls `clearCartStorage()` to reset cart state
- The `push()` method checks if cart is explicitly provided in request before loading from storage
- State change flags (`cartChanged`, `wishlistChanged`) are set when state transitions occur
- Initialization flags are reset when major state changes occur (e.g., checkout completion)

#### 2. RelevaConfig (`config/RelevaConfig.kt`)

Configuration class for SDK features. Provides:
- `full()` - All features enabled (default)
- `trackingOnly()` - Only tracking, no messaging
- `messagingOnly()` - Only messaging, no tracking
- `pushOnly()` - Only push notifications
- Custom configurations with granular control

#### 3. Type Models

**Cart Models (`types/cart/`)**
- `Cart` - Represents shopping cart with products, supports paid/active states
- `CartProduct` - Individual cart item with price, quantity, custom fields

**Filter Models (`types/filter/`)**
- `AbstractFilter` - Base filter interface
- `SimpleFilter` - Single field filters with operators (eq, lt, gt, etc.)
- `NestedFilter` - Complex filters with AND/OR logic
- `FilterOperator` - Comparison operators enum
- `FilterAction` - Filter actions (include, exclude, boost, bury)

**Response Models (`types/response/`)**
- `RelevaResponse` - Main API response container
- `RecommenderResponse` - Product recommendations
- `ProductRecommendation` - Individual product recommendation
- `BannerResponse` - Banner data
- `Template` - Template for rendering recommendations

**Tracking Models (`types/tracking/`)**
- `PushRequest` - Base class for tracking requests with optional cart field
- `ViewedProduct` - Product view data

**Other Models**
- `CustomFields` - Custom field container
- `DeviceType` - Device type enum (ANDROID, iOS, HUAWEI)
- `WishlistProduct` - Wishlist item

#### 4. Services

**StorageService (`services/storage/StorageService.kt`)**

Manages persistent storage using Android SharedPreferences:
- User data (profile ID, device ID)
- Session management (session ID, timestamp)
- Cart and wishlist data
- Pending events (analytics, engagement)
- Settings

**Key Features:**
- Singleton pattern
- Thread-safe operations
- JSON serialization for complex objects

**EngagementTrackingService (`services/engagement/EngagementTrackingService.kt`)**

Tracks push notification engagement:
- Detects Releva notifications
- Tracks notification opens/clicks
- Batches events for efficient delivery
- Offline support with retry logic
- Network connectivity checks

**Key Features:**
- Coroutine-based async operations
- Automatic batching (30-second intervals)
- Offline queue persistence
- OkHttp for network requests

**NavigationService (`services/navigation/NavigationService.kt`)**

Orchestrates push notification navigation:
- Singleton pattern for global access
- Manages NavigationHandler registration
- Parses notification intent extras
- Routes to screens or URLs based on notification data

**RelevaFirebaseMessagingService (`services/navigation/RelevaFirebaseMessagingService.kt`)**

Base FCM service for easy integration:
- Automatic notification display
- Callback URL tracking
- Navigation intent creation
- Extensible with template methods

## Data Flow

### 1. Initialization
```
App Start → MyApplication.onCreate()
  → Create RelevaClient
  → Set device ID
  → Set profile ID (on login)
  → Enable engagement tracking (optional)
```

### 2. Tracking Flow
```
User Action (e.g., view product)
  → Activity/Fragment calls trackProductView()
  → RelevaClient.push()
  → Build context with session, cart, wishlist
  → HTTP POST to /api/v0/push
  → Parse RelevaResponse
  → Return recommendations and banners
```

### 3. Checkout Flow
```
User completes checkout
  → App calls trackCheckoutSuccess(orderedCart)
  → RelevaClient sets cartChanged = true
  → RelevaClient.push() with explicit cart in request
  → SDK automatically calls clearCartStorage()
  → Cart storage cleared with cartChanged = true
  → cartInitialized reset to false
  → Next screen view tracks empty cart
```

### 4. Push Notification Flow
```
FCM Notification Received
  → RelevaFirebaseMessagingService.onMessageReceived()
  → Display notification with intent extras
  → User taps notification
  → MainActivity receives intent
  → NavigationService.handleNotificationNavigation()
  → NavigationHandler routes to screen or URL
  → Callback URL tracking (if provided)
```

### 5. Cart/Wishlist Flow
```
User adds/removes items
  → App calls setCart() or setWishlist()
  → Compare with stored state
  → If different: mark as changed, send automatic push
  → Serialize to JSON
  → StorageService persists data
  → Include in next tracking request
```

## Threading Model

The SDK uses Kotlin Coroutines for asynchronous operations:

- **Main Thread**: Client initialization, synchronous getters
- **IO Dispatcher**: Network requests, storage operations
- **Default Dispatcher**: JSON serialization, data processing

All public tracking methods are `suspend` functions that must be called from a coroutine scope:
```kotlin
lifecycleScope.launch {
    relevaClient.trackProductView(...)
}
```

## Network Layer

Uses OkHttp for HTTP communication:
- 30-second connect timeout
- 30-second read timeout
- Automatic retry for failed requests
- JSON request/response bodies
- Bearer token authentication

## Storage Layer

Uses Android SharedPreferences:
- Singleton access pattern
- Organized into logical boxes (user, analytics, engagement, settings)
- JSON serialization for complex objects
- Thread-safe operations

## Configuration Management

Three levels of configuration:
1. **SDK Config** (`RelevaConfig`) - Feature toggles
2. **Client Config** - Realm, access token
3. **Runtime Config** - Device ID, profile ID, session

## Error Handling

- All network operations wrapped in try-catch
- Exceptions propagated to caller
- Graceful degradation (empty responses when tracking disabled)
- Offline queue for engagement events

## Security Considerations

- Access token stored in memory (not persisted)
- Device/profile IDs stored in SharedPreferences
- HTTPS-only communication
- ProGuard rules protect internal classes
- No sensitive data logging

## Dependencies

### Required
- Kotlin stdlib and coroutines
- AndroidX core and appcompat
- OkHttp for networking
- org.json for JSON parsing

### Optional
- Firebase Messaging (for push notifications)

### Testing
- JUnit for unit tests
- AndroidX Test for instrumentation tests
- Coroutines test support

## Comparison with Flutter SDK

| Feature | Flutter SDK | Kotlin SDK |
|---------|-------------|------------|
| Storage | Hive | SharedPreferences |
| Async | Dart async/await | Kotlin Coroutines |
| HTTP | dart:http | OkHttp |
| UI Components | Included | Not included |
| Platform | Cross-platform | Android only |
| Build System | pub/pubspec | Gradle |
| Serialization | dart:convert | org.json |
| Cart Auto-clear | Manual | Automatic after checkout |

## Build System

### Gradle Files

- `build.gradle.kts` (root) - Plugin versions
- `settings.gradle.kts` - Module configuration
- `releva-sdk/build.gradle.kts` - SDK dependencies and build config
- `gradle.properties` - Build properties

### Build Variants

- **Debug** - Development builds with full logging
- **Release** - Production builds with ProGuard

### Build Commands

```bash
# Build SDK
./gradlew :releva-sdk:build

# Build release AAR
./gradlew :releva-sdk:assembleRelease

# Run tests
./gradlew :releva-sdk:test

# Publish to Maven Local
./gradlew :releva-sdk:publishToMavenLocal
```

## Testing Strategy

### Unit Tests
- Model serialization/deserialization
- Filter logic
- Storage operations
- Configuration validation
- State flag management

### Integration Tests
- API communication
- Engagement tracking
- Session management
- Error handling
- Cart/wishlist state transitions

### Manual Testing
- Product view tracking
- Search tracking
- Checkout flow with cart clearing
- Push notification handling with navigation
- Cart/wishlist updates with automatic push

## Common Integration Patterns

### Automatic Cart/Wishlist Tracking

The SDK automatically detects changes and sends push requests:

```kotlin
// In your Fragment/Activity with LiveData observers
viewModel.cartItems.observe(viewLifecycleOwner) { items ->
    val products = viewModel.cartProducts.value
    if (products != null) {
        RelevaManager.updateCart(items, products)
        // SDK compares with stored state
        // If changed: automatic push with cartChanged=true
    }
}

viewModel.userLikes.observe(viewLifecycleOwner) { likes ->
    RelevaManager.updateWishlist(likes)
    // SDK compares with stored state
    // If changed: automatic push with wishlistChanged=true
}
```

### Preventing Duplicate Tracking

Use a base fragment class for consistent tracking:

```kotlin
abstract class RelevaTrackingFragment : Fragment() {
    abstract fun getPageUrl(): String?
    abstract fun getScreenToken(): String?

    open fun updateTrackingData() {
        // Override to update cart/wishlist before screen view
    }

    open fun trackScreenView() {
        // Override to customize or disable screen tracking
        RelevaManager.trackScreenView(getPageUrl(), getScreenToken())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTrackingData()
        trackScreenView()
    }
}
```

**Important**: When using observers for cart/wishlist, remove duplicate calls from `updateTrackingData()` to prevent double tracking.

### Special Case: Checkout Success

Checkout success requires special handling:

```kotlin
class OrderSuccessFragment : RelevaTrackingFragment() {

    // Prevent automatic screen view tracking
    override fun trackScreenView() {
        // Do nothing - checkout success uses trackCheckoutSuccess()
    }

    override fun updateTrackingData() {
        // Only update wishlist, not cart
        // Cart is explicitly set in trackCheckoutSuccess call
        RelevaManager.updateWishlist(orderViewModel.userLikes.value ?: emptyList())
    }

    private fun trackOrder() {
        lifecycleScope.launch {
            val orderedCart = Cart.paid(cartProducts, orderId = orderId)

            // SDK handles:
            // 1. Sets cartChanged = true
            // 2. Sends push with paid cart
            // 3. Automatically clears cart storage
            // 4. Resets cartInitialized = false
            RelevaManager.trackCheckoutSuccess(
                pageUrl = PageUrls.ORDER_SUCCESS,
                orderedCart = orderedCart
            )
        }
    }
}
```

## Future Enhancements

Potential additions for future versions:
- In-app messaging UI components
- Screen tracking with Android Navigation Component
- Jetpack Compose support
- Automatic activity lifecycle tracking
- Room database integration
- Kotlin Flow/StateFlow APIs
- Background sync with WorkManager

## License

Copyright © Releva.ai
