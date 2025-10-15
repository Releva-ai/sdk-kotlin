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

## Step 5: Implement Push Notifications with Navigation (Recommended)

The Releva SDK provides a complete push notification solution with automatic navigation handling. This is the easiest and recommended approach.

### Overview

The SDK's push notification system includes:
- **Automatic Notification Handling** - Displays push notifications automatically with proper styling
- **Smart Navigation** - Routes to app screens or external URLs based on notification data
- **Callback Tracking** - Automatically tracks notification impressions via callback URLs
- **Easy Integration** - Minimal code required, just extend base service and implement navigation handler

### Navigation Target Types

The SDK supports three types of navigation:
- **`screen`** - Navigate to a specific app screen with optional parameters
- **`url`** - Open external URL in browser
- **default** - Open app to home screen (when no target specified)

### 5.1: Add Firebase

1. Add Firebase to your project following [Firebase setup guide](https://firebase.google.com/docs/android/setup)

2. Add dependencies to `build.gradle.kts`:

```kotlin
dependencies {
    // Firebase
    implementation("com.google.firebase:firebase-messaging:23.4.0")

    // Required for push notifications
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

3. Add POST_NOTIFICATIONS permission to AndroidManifest.xml (Android 13+):

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 5.2: Extend RelevaFirebaseMessagingService

Create your FCM service by extending the SDK's base service:

```kotlin
// MyFirebaseMessagingService.kt
package com.example.myapp.push

import ai.releva.sdk.services.navigation.RelevaFirebaseMessagingService
import com.example.myapp.R
import com.example.myapp.ui.MainActivity

class MyFirebaseMessagingService : RelevaFirebaseMessagingService() {

    // Return your main activity class
    override fun getMainActivityClass(): Class<*> {
        return MainActivity::class.java
    }

    // Return your notification icon resource
    override fun getNotificationIcon(): Int {
        return R.drawable.ic_notification
    }

    // Optional: Customize default notification title
    override fun getDefaultNotificationTitle(): String {
        return "My App"
    }

    // Handle token registration with Releva
    override fun onPushTokenGenerated(token: String) {
        val relevaClient = (application as MyApplication).relevaClient
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                relevaClient.registerPushToken(
                    ai.releva.sdk.types.device.DeviceType.ANDROID,
                    token
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error registering push token", e)
            }
        }
    }

    companion object {
        private const val TAG = "MyFCMService"
    }
}
```

### 5.3: Register Service in AndroidManifest.xml

```xml
<service
    android:name=".push.MyFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### 5.4: Create NavigationHandler

Implement the `NavigationHandler` interface to handle screen navigation from notifications:

```kotlin
// AppNavigationHandler.kt
package com.example.myapp.navigation

import android.os.Bundle
import android.util.Log
import androidx.navigation.NavController
import ai.releva.sdk.services.navigation.NavigationHandler
import com.example.myapp.R

class AppNavigationHandler(private val navController: NavController) : NavigationHandler {

    override fun navigateToScreen(screenName: String, parameters: Bundle) {
        Log.d("AppNavigation", "Navigating to: $screenName")

        val destinationId = getScreenMappings()[screenName] as? Int

        if (destinationId != null) {
            navController.navigate(destinationId, parameters)
        } else {
            // Fallback to home
            navController.navigate(R.id.homeFragment, parameters)
        }
    }

    override fun getScreenMappings(): Map<String, Any> {
        return mapOf(
            // Map notification screen names to navigation IDs
            "home" to R.id.homeFragment,
            "/" to R.id.homeFragment,
            "profile" to R.id.profileFragment,
            "cart" to R.id.cartFragment,
            "product" to R.id.productDetailsFragment,
            "product_details" to R.id.productDetailsFragment,
            "orders" to R.id.ordersFragment,
            "search" to R.id.searchFragment
        )
    }
}
```

### 5.5: Set Up Navigation in MainActivity

Register the navigation handler and handle notification navigation:

```kotlin
// MainActivity.kt
import ai.releva.sdk.services.navigation.NavigationService
import com.example.myapp.navigation.AppNavigationHandler

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up your navigation controller
        val navController = findNavController(R.id.nav_host_fragment)

        // Register navigation handler with SDK
        val navigationHandler = AppNavigationHandler(navController)
        NavigationService.getInstance().setNavigationHandler(navigationHandler)

        // Handle notification navigation (if opened from notification)
        NavigationService.getInstance().handleNotificationNavigation(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle navigation when app is already running
        NavigationService.getInstance().handleNotificationNavigation(this, intent)
    }
}
```

### 5.6: Push Notification Payload Examples

The SDK supports three types of navigation:

#### Navigate to Screen with Parameters

```json
{
  "notification": {
    "title": "New Message",
    "body": "You have a new message"
  },
  "data": {
    "target": "screen",
    "navigate_to_screen": "chat",
    "navigate_to_parameters": "{\"userId\":\"123\",\"messageId\":\"456\"}",
    "callbackUrl": "https://api.example.com/track/impression/abc123"
  },
  "android": {
    "channelId": "default_channel"
  }
}
```

#### Open External URL

```json
{
  "notification": {
    "title": "Special Offer",
    "body": "50% off today only"
  },
  "data": {
    "target": "url",
    "navigate_to_url": "https://example.com/offers",
    "callbackUrl": "https://api.example.com/track/impression/xyz789"
  }
}
```

#### Default (Open App to Home)

```json
{
  "notification": {
    "title": "Welcome Back",
    "body": "Check out what's new"
  },
  "data": {
    "callbackUrl": "https://api.example.com/track/impression/def456"
  }
}
```

### 5.7: What the SDK Handles Automatically

✅ **Notification Display** - Shows notifications with proper icons and styling
✅ **Navigation** - Routes to screens or URLs based on `target` field
✅ **Parameter Parsing** - Converts JSON parameters to Bundle automatically
✅ **Callback Tracking** - Makes GET requests to track impressions
✅ **Channel Management** - Creates and manages notification channels
✅ **Token Registration** - Handles FCM token lifecycle

### 5.8: Advanced Configuration

#### Custom Notification Channel

```kotlin
override fun getNotificationChannelId(): String {
    return "my_custom_channel"
}
```

#### Handle Complex Parameters

Access parameters in your fragments/activities:

```kotlin
class ProductDetailsFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get parameters from notification
        val productId = arguments?.getString("productId")
        val fromNotification = arguments?.getBoolean("fromNotification", false)

        if (fromNotification) {
            // Handle notification-specific logic
        }
    }
}
```

### 5.9: Testing Push Notifications

The SDK supports **two notification payload formats** depending on your requirements:

#### Option 1: Data-Only Messages (Recommended for Maximum Control)

**Best for:** Full SDK control over notification display and navigation in all app states.

**Characteristics:**
- ✅ `onMessageReceived()` called in BOTH foreground and background
- ✅ SDK controls notification display, icons, channels, images
- ✅ Navigation works reliably in all scenarios
- ✅ Recommended for new integrations

```bash
# Test data-only screen navigation
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "data": {
      "title": "New Product Available",
      "body": "Check out our latest products",
      "imageUrl": "https://example.com/product.jpg",
      "target": "screen",
      "navigate_to_screen": "product",
      "navigate_to_parameters": "{\"productId\":\"123\"}",
      "callbackUrl": "https://api.example.com/track/abc"
    }
  }'

# Test data-only URL navigation
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "data": {
      "title": "Visit Our Website",
      "body": "Check out the latest deals",
      "target": "url",
      "navigate_to_url": "https://example.com/offers",
      "callbackUrl": "https://api.example.com/track/xyz"
    }
  }'
```

#### Option 2: Notification + Data Payloads (Cross-Platform Compatible)

**Best for:** Cross-platform compatibility with Flutter SDK, simpler backend.

**Characteristics:**
- ✅ Compatible with existing Flutter SDK implementations
- ✅ Single payload format for both Android and Flutter
- ⚠️ Foreground: SDK displays notification
- ⚠️ Background: FCM displays automatically, MainActivity handles navigation
- ⚠️ Less control over notification appearance in background

**⚠️ IMPORTANT - Required for Android 12+:**
When using notification payloads, you **MUST** add `android.notification.click_action` to avoid Background Activity Launch restrictions:

```bash
# Test notification + data screen navigation
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "notification": {
      "title": "New Product Available",
      "body": "Check out our latest products",
      "image": "https://example.com/product.jpg"
    },
    "data": {
      "click_action": "RELEVA_NOTIFICATION_CLICK",
      "target": "screen",
      "navigate_to_screen": "product",
      "navigate_to_parameters": "{\"productId\":\"123\"}",
      "callbackUrl": "https://api.example.com/track/abc"
    },
    "android": {
      "notification": {
        "click_action": "RELEVA_NOTIFICATION_CLICK"
      }
    }
  }'

# Test notification + data URL navigation
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "notification": {
      "title": "Visit Our Website",
      "body": "Check out the latest deals"
    },
    "data": {
      "click_action": "RELEVA_NOTIFICATION_CLICK",
      "target": "url",
      "navigate_to_url": "https://example.com/offers",
      "callbackUrl": "https://api.example.com/track/xyz"
    },
    "android": {
      "notification": {
        "click_action": "RELEVA_NOTIFICATION_CLICK"
      }
    }
  }'
```

**Why `click_action` is required:**
- Android 12+ blocks "trampoline" activity launches from notifications (Background Activity Launch restrictions)
- Without `click_action`, FCM launches your app's launcher activity which gets blocked
- With `click_action: "RELEVA_NOTIFICATION_CLICK"`, FCM sends intent to MainActivity which bypasses the restriction
- Flutter SDK ignores this field (no breaking changes for Flutter apps)

#### How Notification Payloads Work

**With Data-Only Messages:**
1. FCM delivers message → `onMessageReceived()` called (foreground AND background)
2. SDK builds notification with custom PendingIntent
3. User taps → NavigationService routes to screen/URL

**With Notification + Data Payloads:**
1. **App in Foreground:**
   - `onMessageReceived()` called → SDK builds notification with custom navigation
2. **App in Background:**
   - FCM displays notification automatically
   - User taps → MainActivity receives `data` payload as intent extras
   - `NavigationService.handleNotificationNavigation()` extracts extras and routes

**Important:** When using notification payloads, MainActivity MUST call:
```kotlin
NavigationService.getInstance().handleNotificationNavigation(this, intent)
```
in both `onCreate()` and `onNewIntent()` methods.

### 5.10: Troubleshooting Push Notifications

#### Notifications Not Appearing

1. **Check POST_NOTIFICATIONS permission** (Android 13+)
   - Verify permission is declared in AndroidManifest.xml
   - Request runtime permission if targeting API 33+
2. **Verify notification channels are created**
   - Check logs for channel creation
   - Ensure channel ID matches between SDK and notification payload
3. **Check FCM token is registered**
   - Verify `onPushTokenGenerated()` is called
   - Ensure token is sent to Releva backend
4. **Look for logs** with tag `RelevaFCMService` for debugging info

#### Screen Navigation Not Working

1. **Verify NavigationHandler is set**
   - Check logs for "Navigation handler set" message
   - Ensure `setNavigationHandler()` is called in MainActivity.onCreate()
2. **Check screen name mapping**
   - Verify screen name in notification matches mapping in `getScreenMappings()`
   - Screen names are case-sensitive
3. **Verify NavController is available**
   - NavigationHandler must have valid NavController reference
   - NavController must be set up before handling navigation

#### URL Not Opening

1. **Check URL format**
   - Must be valid HTTP/HTTPS URL
   - Verify no typos in `navigate_to_url` field
2. **Verify `navigate_to_url` field exists** in notification payload
3. **Check browser availability** - Device must have a browser app installed

#### Parameters Not Received

1. **Verify JSON format** in `navigate_to_parameters` field
2. **Check parameter types** - Ensure correct Bundle methods are used to retrieve values
3. **Look for parsing errors** in logs

### 5.11: Migration from App-Level Implementation

If you previously had custom notification handling in your app:

1. **Remove** your custom FirebaseMessagingService implementation
2. **Extend** SDK's `RelevaFirebaseMessagingService` instead
3. **Create** NavigationHandler implementation for your app's navigation structure
4. **Register** NavigationHandler in MainActivity
5. **Remove** custom notification display logic - SDK handles this automatically
6. **Update** notification payload format to match SDK expectations

The SDK handles all notification display and navigation automatically, significantly reducing boilerplate code!

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
