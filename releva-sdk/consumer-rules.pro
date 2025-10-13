# Consumer ProGuard rules for Releva SDK

# Keep all public SDK APIs
-keep public class ai.releva.sdk.client.RelevaClient { public *; }
-keep public class ai.releva.sdk.config.RelevaConfig { public *; }
-keep public class ai.releva.sdk.types.** { public *; }
-keep public class ai.releva.sdk.services.** { public *; }
