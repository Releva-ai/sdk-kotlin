# SDK Development Guide

This guide is for developers maintaining and extending the Releva SDK.

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
├── README.md                                     # Project overview
├── INTEGRATION_GUIDE.md                          # App developer integration guide
├── DEVELOPMENT.md                                # This file
└── RELEASING.md                                  # Release process guide
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

## Technology Stack

| Component | Technology |
|-----------|------------|
| Storage | SharedPreferences |
| Async Operations | Kotlin Coroutines |
| HTTP Client | OkHttp |
| UI Components | Not included (data-only SDK) |
| Platform | Android only |
| Build System | Gradle |
| Serialization | org.json |
| Cart Management | Automatic clearing after checkout |

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

## Contributing

See [RELEASING.md](RELEASING.md) for information on releasing new versions of the SDK.

## License

Copyright © Releva.ai
