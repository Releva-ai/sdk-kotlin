package ai.releva.sdk.services.banner

import android.util.Log

/**
 * Session-scoped record of the banner tokens already displayed.
 *
 * [BannerManagerService] is instantiated by the host app and re-initialized on every push
 * response that carries banners, so neither the instance nor its `initialize()` call can own
 * this set: a screen view would wipe it and a one-time banner would show again. It lives here
 * instead, and [startNewSession] is called by
 * [ai.releva.sdk.services.session.SessionService] when a new session begins.
 *
 * [markShown] is called from `BannerDisplayManager.showBanner`
 * (`ai.releva.sdk.ui.banner.BannerDisplayManager`), not from [BannerManagerService], because the
 * display side is the only place that knows a banner actually rendered rather than merely being
 * emitted into `BannerDisplayController`.
 */
internal object BannerSessionStore {
    private const val TAG = "BannerSessionStore"

    // ConcurrentHashMap-backed: SessionService.startNewSession() can clear this from an IO
    // dispatcher (RelevaClient.ensureSessionTracking() runs inside withContext(Dispatchers.IO))
    // while markShown()/isShown() run on the main dispatcher via BannerManagerService. A plain
    // mutableSetOf() has no happens-before edge across that, so a clear() on IO would not be
    // guaranteed visible on main.
    private val shownTokens: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun isShown(token: String): Boolean = shownTokens.contains(token)

    fun markShown(token: String) {
        shownTokens.add(token)
    }

    /** Clears the shown tokens so every banner can display once in the new session. */
    fun startNewSession() {
        Log.d(TAG, "New session — clearing ${shownTokens.size} shown banner token(s)")
        shownTokens.clear()
    }
}
