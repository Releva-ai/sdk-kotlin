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
 */
internal object BannerSessionStore {
    private const val TAG = "BannerSessionStore"

    private val shownTokens = mutableSetOf<String>()

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
