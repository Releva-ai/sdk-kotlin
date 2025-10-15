# Releva SDK - Push Notification Integration Guide

This guide explains how to integrate Releva SDK's push notification system with automatic navigation handling.

## Overview

The Releva SDK provides a complete push notification solution with:
- Automatic notification display
- Smart navigation (screen navigation or URL opening)
- Callback tracking for notification impressions
- Easy integration with minimal code

## Features

### 1. **Automatic Notification Handling**
- Displays push notifications automatically
- Handles both notification and data-only payloads
- Tracks notification impressions via callback URLs

### 2. **Smart Navigation**
Three types of navigation targets:
- **`screen`** - Navigate to a specific app screen with optional parameters
- **`url`** - Open external URL in browser
- **default** - Open app to home screen

### 3. **Customizable**
- Custom notification icon
- Custom notification title
- Custom screen mappings
- Custom notification channels

## Integration Steps

### Step 1: Extend RelevaFirebaseMessagingService

Create your FCM service class that extends the SDK's base service:

```kotlin
package com.yourapp.push

import ai.releva.sdk.services.navigation.RelevaFirebaseMessagingService
import com.yourapp.R
import com.yourapp.ui.MainActivity

class MyFirebaseMessagingService : RelevaFirebaseMessagingService() {

    // Return your main activity class
    override fun getMainActivityClass(): Class<*> {
        return MainActivity::class.java
    }

    // Return your notification icon resource
    override fun getNotificationIcon(): Int {
        return R.drawable.ic_notification
    }

    // Optional: Customize notification title
    override fun getDefaultNotificationTitle(): String {
        return "My App"
    }

    // Handle token registration with your backend
    override fun onPushTokenGenerated(token: String) {
        // Register token with Releva or your backend
        RelevaManager.registerPushToken(token)
    }
}
```

### Step 2: Register Service in AndroidManifest.xml

```xml
<service
    android:name=".push.MyFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### Step 3: Add POST_NOTIFICATIONS Permission

For Android 13+ (API 33):

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Step 4: Create NavigationHandler

Implement the `NavigationHandler` interface to handle screen navigation:

```kotlin
package com.yourapp.navigation

import android.os.Bundle
import androidx.navigation.NavController
import ai.releva.sdk.services.navigation.NavigationHandler
import com.yourapp.R

class AppNavigationHandler(private val navController: NavController) : NavigationHandler {

    override fun navigateToScreen(screenName: String, parameters: Bundle) {
        val destinationId = getScreenMappings()[screenName] as? Int

        if (destinationId != null) {
            navController.navigate(destinationId, parameters)
        }
    }

    override fun getScreenMappings(): Map<String, Any> {
        return mapOf(
            "home" to R.id.homeFragment,
            "profile" to R.id.profileFragment,
            "cart" to R.id.cartFragment,
            "product" to R.id.productDetailsFragment
            // Add more screen mappings as needed
        )
    }
}
```

### Step 5: Set Up Navigation in MainActivity

```kotlin
import ai.releva.sdk.services.navigation.NavigationService
import com.yourapp.navigation.AppNavigationHandler

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up your navigation
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

## Push Notification Payload Examples

### Example 1: Navigate to Screen

```json
{
  "notification": {
    "title": "New Message",
    "body": "You have a new message from John"
  },
  "data": {
    "target": "screen",
    "navigate_to_screen": "chat",
    "navigate_to_parameters": "{\"userId\":\"123\",\"chatId\":\"456\"}",
    "callbackUrl": "https://api.example.com/track/impression/abc123"
  },
  "android": {
    "channelId": "default_channel"
  }
}
```

### Example 2: Open URL

```json
{
  "notification": {
    "title": "Special Offer!",
    "body": "50% off on all items"
  },
  "data": {
    "target": "url",
    "navigate_to_url": "https://example.com/offers",
    "callbackUrl": "https://api.example.com/track/impression/xyz789"
  }
}
```

### Example 3: Default (Open App)

```json
{
  "notification": {
    "title": "Welcome Back!",
    "body": "We've missed you"
  },
  "data": {
    "callbackUrl": "https://api.example.com/track/impression/def456"
  }
}
```

## Navigation Parameters

### Screen Navigation (`target: "screen"`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `target` | string | Yes | Must be `"screen"` |
| `navigate_to_screen` | string | Yes | Screen name as defined in NavigationHandler |
| `navigate_to_parameters` | string | No | JSON-encoded object with parameters |

**Example parameters:**
```json
{
  "productId": "12345",
  "category": "electronics",
  "fromNotification": "true"
}
```

### URL Navigation (`target: "url"`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `target` | string | Yes | Must be `"url"` |
| `navigate_to_url` | string | Yes | Full URL to open in browser |

### Callback Tracking

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `callbackUrl` | string | No | URL to call (GET request) when notification is displayed |

The SDK automatically makes a GET request to this URL to track impressions.

## Advanced Configuration

### Custom Notification Channel

Override the channel ID:

```kotlin
override fun getNotificationChannelId(): String {
    return "my_custom_channel"
}
```

### Handle Complex Parameters

In your NavigationHandler, you can access all parameter types:

```kotlin
override fun navigateToScreen(screenName: String, parameters: Bundle) {
    val productId = parameters.getString("productId")
    val quantity = parameters.getInt("quantity", 1)
    val isPromo = parameters.getBoolean("isPromo", false)

    // Use parameters for navigation
    val bundle = bundleOf(
        "productId" to productId,
        "quantity" to quantity,
        "isPromo" to isPromo
    )

    navController.navigate(R.id.productDetailsFragment, bundle)
}
```

## Testing

### 1. Test Default Navigation
Send notification without `target` field - should open app to home screen

### 2. Test Screen Navigation
```bash
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "notification": {
      "title": "Test",
      "body": "Screen navigation test"
    },
    "data": {
      "target": "screen",
      "navigate_to_screen": "profile"
    }
  }'
```

### 3. Test URL Navigation
```bash
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=YOUR_FCM_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "DEVICE_TOKEN",
    "notification": {
      "title": "Test",
      "body": "URL navigation test"
    },
    "data": {
      "target": "url",
      "navigate_to_url": "https://google.com"
    }
  }'
```

## Troubleshooting

### Notifications Not Appearing

1. **Check POST_NOTIFICATIONS permission** (Android 13+)
2. **Verify notification channels are created**
3. **Check FCM token is registered**
4. **Look for logs** with tag `RelevaFCMService`

### Screen Navigation Not Working

1. **Verify NavigationHandler is set** - Check logs for "Navigation handler set"
2. **Check screen name mapping** - Ensure screen name matches mapping
3. **Verify NavController is available** - Must be set before handling navigation

### URL Not Opening

1. **Check URL format** - Must be valid HTTP/HTTPS URL
2. **Verify `navigate_to_url` field** exists in payload

## Migration from App-Level Implementation

If you previously had notification handling in your app:

1. **Remove** custom FirebaseMessagingService implementation
2. **Extend** SDK's `RelevaFirebaseMessagingService` instead
3. **Create** NavigationHandler implementation
4. **Register** handler in MainActivity
5. **Remove** custom notification display logic

The SDK handles everything automatically!

## Support

For issues or questions:
- Check SDK logs with tag `RelevaFCMService` and `NavigationService`
- Ensure SDK version is up to date
- Review this integration guide
