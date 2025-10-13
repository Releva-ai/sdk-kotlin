# Releva SDK Integration Guide for Android

This guide provides step-by-step instructions for integrating the Releva SDK into your Android application.

## Prerequisites

- Android Studio Arctic Fox or later
- Minimum SDK: API 21 (Android 5.0)
- Target SDK: API 34
- Kotlin 1.9+
- Gradle 8.0+

## Step 1: Add the SDK to Your Project

### Option A: Include as Module

1. Copy the `releva-sdk` directory into your project root
2. Add to your `settings.gradle.kts`:
```kotlin
include(":app", ":releva-sdk")
```

3. Add dependency in your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":releva-sdk"))

    // Required dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Optional: Firebase for push notifications
    implementation("com.google.firebase:firebase-messaging:23.4.0")
}
```

### Option B: Build AAR Library

1. In the `releva-sdk` directory, run:
```bash
./gradlew assembleRelease
```

2. Find the AAR file at:
```
releva-sdk/build/outputs/aar/releva-sdk-release.aar
```

3. Add to your app:
```kotlin
dependencies {
    implementation(files("libs/releva-sdk-release.aar"))
    // Add required dependencies...
}
```

## Step 2: Initialize the SDK

### Create Application Class

Create or update your Application class:

```kotlin
// MyApplication.kt
package com.example.myapp

import android.app.Application
import ai.releva.sdk.client.RelevaClient
import ai.releva.sdk.config.RelevaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {

    lateinit var relevaClient: RelevaClient
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Initialize Releva SDK
        relevaClient = RelevaClient(
            context = applicationContext,
            realm = "",  // Usually empty, provided by Releva
            accessToken = "YOUR_ACCESS_TOKEN",  // Get from Releva dashboard
            config = RelevaConfig.full()
        )

        // Set user identifiers
        applicationScope.launch {
            // Set device ID (use Android ID or UUID)
            val deviceId = getDeviceId()
            relevaClient.setDeviceId(deviceId)

            // Set profile ID when user logs in
            // relevaClient.setProfileId("user-123")

            // Enable push tracking (optional)
            relevaClient.enablePushEngagementTracking()
        }
    }

    private fun getDeviceId(): String {
        // Use Android ID or generate UUID
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }
}
```

### Register Application Class

Update your `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapp">

    <application
        android:name=".MyApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">

        <!-- Your activities -->

    </application>
</manifest>
```

## Step 3: Track User Actions

### In Activities

```kotlin
class ProductDetailActivity : AppCompatActivity() {

    private val relevaClient by lazy {
        (application as MyApplication).relevaClient
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product_detail)

        // Track product view
        lifecycleScope.launch {
            try {
                val response = relevaClient.trackProductView(
                    screenToken = "product_detail",
                    productId = productId,
                    categories = listOf("electronics", "phones")
                )

                // Display recommendations
                if (response.hasRecommenders) {
                    displayRecommendations(response.recommenders)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking product view", e)
            }
        }
    }
}
```

### In Fragments

```kotlin
class SearchFragment : Fragment() {

    private val relevaClient by lazy {
        (requireActivity().application as MyApplication).relevaClient
    }

    private fun performSearch(query: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = relevaClient.trackSearchView(
                    screenToken = "search_results",
                    query = query
                )

                displaySearchResults(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking search", e)
            }
        }
    }
}
```

### In ViewModels

```kotlin
class ProductViewModel : ViewModel() {

    fun trackProductView(relevaClient: RelevaClient, productId: String) {
        viewModelScope.launch {
            try {
                val response = relevaClient.trackProductView(
                    screenToken = "product_detail",
                    productId = productId
                )

                _recommendations.value = response.recommenders
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking product view", e)
            }
        }
    }
}
```

## Step 4: Track Cart and Wishlist

```kotlin
class CartManager(private val relevaClient: RelevaClient) {

    suspend fun updateCart(items: List<CartItem>) {
        val cartProducts = items.map { item ->
            CartProduct(
                id = item.productId,
                price = item.price,
                quantity = item.quantity.toDouble()
            )
        }

        val cart = Cart.active(cartProducts)
        relevaClient.setCart(cart)
    }

    suspend fun updateWishlist(productIds: List<String>) {
        val wishlistProducts = productIds.map { WishlistProduct(id = it) }
        relevaClient.setWishlist(wishlistProducts)
    }
}
```

## Step 5: Implement Push Notifications (Optional)

### Add Firebase

1. Add Firebase to your project following [Firebase setup guide](https://firebase.google.com/docs/android/setup)

2. Implement FirebaseMessagingService:

```kotlin
class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Register token with Releva
        scope.launch {
            try {
                val relevaClient = (application as MyApplication).relevaClient
                relevaClient.registerPushToken(DeviceType.ANDROID, token)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering push token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val relevaClient = (application as MyApplication).relevaClient
        val engagementService = relevaClient.engagementTrackingService

        // Track Releva push notification engagement
        if (engagementService?.isRelevaMessage(message.data) == true) {
            scope.launch {
                engagementService.trackEngagement(message.data)
            }
        }

        // Show notification
        showNotification(message)
    }

    private fun showNotification(message: RemoteMessage) {
        // Implement notification display
    }
}
```

3. Register service in AndroidManifest.xml:

```xml
<service
    android:name=".MyFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

## Step 6: Handle User Authentication

```kotlin
class AuthManager(private val relevaClient: RelevaClient) {

    suspend fun onUserLogin(userId: String) {
        // Set profile ID when user logs in
        relevaClient.setProfileId(userId)

        // Optionally register push token
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            lifecycleScope.launch {
                relevaClient.registerPushToken(DeviceType.ANDROID, token)
            }
        }
    }

    suspend fun onUserLogout() {
        // Clear profile ID
        relevaClient.setProfileId("")
    }
}
```

## Step 7: Advanced Features

### Complex Filtering

```kotlin
import ai.releva.sdk.types.filter.*

val filter = NestedFilter.and(listOf(
    SimpleFilter.priceRange(minPrice = 50.0, maxPrice = 200.0),
    NestedFilter.or(listOf(
        SimpleFilter.brand(brand = "Nike"),
        SimpleFilter.brand(brand = "Adidas")
    )),
    SimpleFilter.color(color = "red")
))

lifecycleScope.launch {
    val response = relevaClient.trackSearchView(
        screenToken = "filtered_products",
        filter = filter
    )
}
```

### Custom Fields

```kotlin
import ai.releva.sdk.types.customfield.CustomFields

val customFields = mapOf(
    "material" to "cotton",
    "season" to "summer",
    "rating" to 4.5
)

lifecycleScope.launch {
    relevaClient.trackProductView(
        screenToken = "product_detail",
        productId = "123",
        customFields = customFields
    )
}
```

## Testing

### Debug Logging

Enable logging to see SDK activity:

```kotlin
// In Application.onCreate()
if (BuildConfig.DEBUG) {
    // Enable OkHttp logging
    val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
}
```

### Verify Integration

1. Track a product view and check logs
2. Verify data in Releva dashboard
3. Test recommendations response
4. Test push notification tracking

## Configuration Options

Choose the right configuration for your use case:

```kotlin
// Full functionality (recommended)
RelevaConfig.full()

// Tracking only (no push notifications)
RelevaConfig.trackingOnly()

// Custom configuration
RelevaConfig(
    enableTracking = true,
    enableScreenTracking = true,
    enableInAppMessaging = false,
    enablePushNotifications = true,
    enableAnalytics = true
)
```

## Performance Best Practices

1. **Use Coroutines**: All SDK calls are suspend functions - use appropriate coroutine scopes
2. **Avoid Main Thread**: Long-running operations are handled on IO dispatcher
3. **Cache Client Instance**: Store RelevaClient in Application class
4. **Batch Updates**: Update cart/wishlist in batches rather than individual items
5. **Error Handling**: Always wrap SDK calls in try-catch blocks

## Troubleshooting

### Common Issues

**Issue**: `DeviceId not set` exception
- **Solution**: Call `setDeviceId()` before any tracking methods

**Issue**: Network timeout
- **Solution**: Check internet connectivity, verify access token

**Issue**: ProGuard strips SDK classes
- **Solution**: Ensure ProGuard rules are applied (see README)

**Issue**: Coroutine scope issues
- **Solution**: Use appropriate lifecycle-aware scopes (lifecycleScope, viewModelScope)

## Migration from Flutter SDK

Key differences when migrating from Flutter:

1. **Async/Await → Coroutines**: Use `suspend` functions with coroutine scopes
2. **No UI Components**: SDK focuses on tracking and data APIs
3. **Storage**: Uses SharedPreferences instead of Hive
4. **Networking**: Uses OkHttp instead of Dart http

## Support

For questions or issues:
- Email: tech-support@releva.ai
- Website: https://releva.ai
- Documentation: https://docs.releva.ai

## Next Steps

1. ✅ Add SDK to project
2. ✅ Initialize in Application class
3. ✅ Set device and profile IDs
4. ✅ Track product views
5. ✅ Track search and checkout
6. ✅ Implement push notifications
7. ✅ Test in production

Happy integrating! 🚀
