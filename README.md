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

## License

Copyright © Releva.ai
