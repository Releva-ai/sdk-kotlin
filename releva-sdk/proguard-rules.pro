# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep all public classes and methods in the SDK
-keep public class ai.releva.sdk.** { public *; }

# Keep data classes
-keepclassmembers class ai.releva.sdk.types.** {
    *;
}

# Keep enums
-keepclassmembers enum ai.releva.sdk.types.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Firebase (if used)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
