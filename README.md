# Releva SDK for Android (Kotlin)

Native Android SDK for the Releva marketing platform, providing e-commerce tracking, personalization, and analytics.

**Current Version:** 1.0.2

## Features

- 🎯 **E-commerce Personalization** - AI-powered product recommendations and dynamic content
- 📱 **Mobile Tracking & Analytics** - Screen tracking, product views, search, checkout events
- 🔔 **Push Notifications** - FCM integration with engagement tracking
- ⚙️ **Flexible Configuration** - Modular setup for tracking-only, messaging-only, or full-featured modes

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
    implementation("com.github.Releva-ai:sdk-kotlin:1.0.0")
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

### Configuration Options
```kotlin
RelevaConfig.full()          // All features
RelevaConfig.trackingOnly()  // Analytics only
RelevaConfig.pushOnly()      // Push notifications only
```

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
