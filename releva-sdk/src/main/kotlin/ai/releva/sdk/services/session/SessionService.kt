package ai.releva.sdk.services.session

import ai.releva.sdk.services.nps.NpsManagerService
import ai.releva.sdk.services.storage.StorageService
import android.util.Log
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
    private var pausedAtMs: Long? = null

    fun initialize(storage: StorageService, npsManager: NpsManagerService) {
        if (initialized) return
        this.storage = storage
        this.npsManager = npsManager
        initialized = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Cold start = new session
        startNewSession()

        Log.d(TAG, "Initialized")
    }

    override fun onStop(owner: LifecycleOwner) {
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
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            storage.setDeviceFirstSeenAt(sdf.format(Date()))
        }

        // Increment session count
        val count = storage.getDeviceSessionCount()
        storage.setDeviceSessionCount(count + 1)
        storage.setDeviceLastSessionTimestamp(now)

        // Generate new session ID
        val sessionId = UUID.randomUUID().toString()
        storage.setSessionId(sessionId)
        storage.setSessionTimestamp(now)

        npsManager?.startNewSession()

        Log.d(TAG, "New session #${count + 1}, id=$sessionId")
    }

    /**
     * Returns the current session ID from storage.
     * Falls back to generating one if none exists.
     */
    fun getSessionId(): String {
        val existing = storage?.getSessionId()
        if (existing != null) return existing

        // Safety fallback
        val sessionId = UUID.randomUUID().toString()
        storage?.setSessionId(sessionId)
        storage?.setSessionTimestamp(System.currentTimeMillis())
        return sessionId
    }

    fun dispose() {
        if (initialized) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        initialized = false
        instance = null
    }
}
