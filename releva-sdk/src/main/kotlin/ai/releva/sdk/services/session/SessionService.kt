package ai.releva.sdk.services.session

import ai.releva.sdk.services.nps.NpsManagerService
import ai.releva.sdk.services.storage.StorageService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tracks device sessions based on app lifecycle events.
 *
 * A new session is counted when the app returns to the foreground after being
 * in the background for longer than [DEBOUNCE_THRESHOLD_MS] (or on first-ever
 * cold start). Each new session generates a fresh sessionId (UUID) and
 * increments the persistent device session count.
 */
class SessionService private constructor() : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "SessionService"
        private const val DEBOUNCE_THRESHOLD_MS = 30_000L // 30 seconds
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).also {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }

        @Volatile
        private var instance: SessionService? = null

        fun getInstance(): SessionService {
            return instance ?: synchronized(this) {
                instance ?: SessionService().also { instance = it }
            }
        }
    }

    private var storage: StorageService? = null
    private var npsManager: NpsManagerService? = null
    private var initialized = false
    @Volatile private var pausedAtMs: Long? = null
    @Volatile private var fallbackSessionId: String? = null

    fun initialize(storage: StorageService, npsManager: NpsManagerService) {
        synchronized(this) {
            if (initialized) return
            this.storage = storage
            this.npsManager = npsManager
            initialized = true
        }

        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        // Cold start = new session.
        // Note: startNewSession() runs after the synchronized block (and after initialized = true).
        // This is intentional — the lock prevents a second thread from ever reaching this point,
        // because it will see initialized = true and return early. startNewSession() is always
        // called exactly once per initialize() invocation.
        startNewSession()

        Log.d(TAG, "Initialized")
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!initialized) return
        pausedAtMs = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        val paused = pausedAtMs
        if (paused != null && (now - paused) > DEBOUNCE_THRESHOLD_MS) {
            startNewSession()
        }
        pausedAtMs = null
    }

    private fun startNewSession() {
        val storage = this.storage ?: return
        val now = System.currentTimeMillis()

        // Record first-seen date on first ever session
        if (storage.getDeviceFirstSeenAt() == null) {
            storage.setDeviceFirstSeenAt(DATE_FORMAT.format(Date()))
        }

        // Increment session count (atomic)
        val count = storage.incrementDeviceSessionCount()
        storage.setDeviceLastSessionTimestamp(now)

        // Generate new session ID
        val sessionId = UUID.randomUUID().toString()
        storage.setSessionId(sessionId)
        storage.setSessionTimestamp(now)

        npsManager?.startNewSession()

        Log.d(TAG, "New session $count, id=$sessionId")
    }

    /**
     * Returns the current session ID from storage.
     * Falls back to generating one if none exists.
     */
    fun getSessionId(): String {
        val existing = storage?.getSessionId()
        if (existing != null) return existing

        // Safety fallback — cache the ID so repeated pre-init calls return the same value
        val cached = fallbackSessionId
        if (cached != null) return cached
        val sessionId = UUID.randomUUID().toString()
        fallbackSessionId = sessionId
        return sessionId
    }

    /**
     * Sets the timestamp of the last pause, used by tests to simulate elapsed background time
     * without sleeping. Avoids reflection on the private [pausedAtMs] field.
     */
    @VisibleForTesting
    internal fun setLastPauseMs(ms: Long) {
        pausedAtMs = ms
    }

    fun dispose() {
        if (initialized) {
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
            }
        }
        initialized = false
        storage = null
        npsManager = null
    }
}
