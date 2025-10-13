# Releva Android SDK - Project Structure

This document provides an overview of the project structure and the purpose of each component.

## Directory Structure

```
kotlin_project/
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
│   │       │       └── storage/
│   │       │           └── StorageService.kt     # Local storage
│   │       ├── res/                              # Android resources
│   │       └── AndroidManifest.xml               # SDK manifest
│   ├── build.gradle.kts                          # SDK build configuration
│   ├── proguard-rules.pro                        # ProGuard rules
│   └── consumer-rules.pro                        # Consumer ProGuard rules
├── build.gradle.kts                              # Root build configuration
├── settings.gradle.kts                           # Gradle settings
├── gradle.properties                             # Gradle properties
├── .gitignore                                    # Git ignore rules
├── README.md                                     # User documentation
├── INTEGRATION_GUIDE.md                          # Detailed integration guide
└── PROJECT_STRUCTURE.md                          # This file
```

## Core Components

### 1. RelevaClient (`client/RelevaClient.kt`)

The main entry point for all SDK functionality. Handles:
- User identification (device ID, profile ID)
- E-commerce tracking (product views, searches, checkout)
- Cart and wishlist management
- API communication
- Session management

**Key Methods:**
- `setDeviceId()` - Set unique device identifier
- `setProfileId()` - Set user profile identifier
- `trackProductView()` - Track product page views
- `trackSearchView()` - Track search queries
- `trackCheckoutSuccess()` - Track completed orders
- `setCart()` - Update shopping cart
- `setWishlist()` - Update wishlist
- `registerPushToken()` - Register FCM token
- `bannerClicked()` - Track banner interactions

### 2. RelevaConfig (`config/RelevaConfig.kt`)

Configuration class for SDK features. Provides:
- `full()` - All features enabled (default)
- `trackingOnly()` - Only tracking, no messaging
- `messagingOnly()` - Only messaging, no tracking
- `pushOnly()` - Only push notifications
- Custom configurations with granular control

### 3. Type Models

#### Cart Models (`types/cart/`)
- `Cart` - Represents shopping cart with products
- `CartProduct` - Individual cart item with price, quantity, custom fields

#### Filter Models (`types/filter/`)
- `AbstractFilter` - Base filter interface
- `SimpleFilter` - Single field filters with operators (eq, lt, gt, etc.)
- `NestedFilter` - Complex filters with AND/OR logic
- `FilterOperator` - Comparison operators enum
- `FilterAction` - Filter actions (include, exclude, boost, bury)

#### Response Models (`types/response/`)
- `RelevaResponse` - Main API response container
- `RecommenderResponse` - Product recommendations
- `ProductRecommendation` - Individual product recommendation
- `BannerResponse` - Banner data
- `Template` - Template for rendering recommendations

#### Tracking Models (`types/tracking/`)
- `PushRequest` - Base class for tracking requests
- `ScreenViewRequest` - Screen/page view tracking
- `SearchRequest` - Search tracking
- `CheckoutSuccessRequest` - Checkout completion tracking
- `ViewedProduct` - Product view data

#### Other Models
- `CustomFields` - Custom field container
- `DeviceType` - Device type enum (ANDROID, iOS, HUAWEI)
- `WishlistProduct` - Wishlist item

### 4. Services

#### StorageService (`services/storage/StorageService.kt`)

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

#### EngagementTrackingService (`services/engagement/EngagementTrackingService.kt`)

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

### 3. Push Notification Flow
```
FCM Notification Received
  → MyFirebaseMessagingService.onMessageReceived()
  → Check if Releva message
  → EngagementTrackingService.trackEngagement()
  → Add to pending events queue
  → Batch send to callback URL
```

### 4. Cart/Wishlist Flow
```
User adds/removes items
  → App calls setCart() or setWishlist()
  → Serialize to JSON
  → StorageService persists data
  → Mark as changed
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

### Integration Tests
- API communication
- Engagement tracking
- Session management
- Error handling

### Manual Testing
- Product view tracking
- Search tracking
- Checkout flow
- Push notification handling
- Cart/wishlist updates

## Future Enhancements

Potential additions for future versions:
- In-app messaging UI components
- Screen tracking with Android Navigation
- Jetpack Compose support
- Automatic activity lifecycle tracking
- Room database integration
- Kotlin Flow/StateFlow APIs
- Background sync with WorkManager

## Versioning

- Current version: 0.0.1-kotlin
- Based on Flutter SDK: 0.0.34
- Follows semantic versioning (MAJOR.MINOR.PATCH)

## License

Copyright © Releva.ai

## Support

- Technical documentation: README.md
- Integration guide: INTEGRATION_GUIDE.md
- Email: tech-support@releva.ai
- Website: https://releva.ai
