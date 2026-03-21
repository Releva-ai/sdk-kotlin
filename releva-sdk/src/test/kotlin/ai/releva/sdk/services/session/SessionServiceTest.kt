package ai.releva.sdk.services.session

import ai.releva.sdk.services.nps.NpsManagerService
import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionServiceTest {

    private lateinit var context: Context
    private lateinit var storage: StorageService
    private lateinit var npsManager: NpsManagerService
    private lateinit var service: SessionService

    /** Minimal LifecycleOwner stub — only used as a parameter; SessionService ignores the owner. */
    private class StubLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private val stubOwner = StubLifecycleOwner()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageService.getInstance(context)
        storage.clear()
        npsManager = NpsManagerService()
        service = SessionService.getInstance()
        service.dispose() // reset initialized/storage state if left over from a previous test
        storage.clear()   // clear again after dispose (dispose() does not touch storage)
    }

    @After
    fun tearDown() {
        service.dispose()
        storage.clear()
    }

    // ── Cold Start ──────────────────────────────────────────────────────────────

    @Test
    fun `cold start sets session count to 1`() {
        service.initialize(storage, npsManager)

        assertEquals(1, storage.getDeviceSessionCount())
    }

    @Test
    fun `cold start generates a session id`() {
        service.initialize(storage, npsManager)

        assertNotNull(storage.getSessionId())
    }

    @Test
    fun `cold start records first seen at`() {
        service.initialize(storage, npsManager)

        assertNotNull(storage.getDeviceFirstSeenAt())
    }

    @Test
    fun `cold start first seen at follows ISO 8601 format`() {
        service.initialize(storage, npsManager)

        val firstSeenAt = storage.getDeviceFirstSeenAt()!!
        // Matches yyyy-MM-ddTHH:mm:ss.SSSZ (e.g. 2026-03-19T12:34:56.789+0000)
        assertTrue(
            "Expected ISO 8601 timestamp, got: $firstSeenAt",
            firstSeenAt.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+\-Z].*"""))
        )
    }

    // ── Foreground After Long Background (>30 min) ────────────────────────────────

    @Test
    fun `foreground after more than 30min creates new session`() {
        service.initialize(storage, npsManager)
        assertEquals(1, storage.getDeviceSessionCount())

        service.onStop(stubOwner)
        backdateLastPause(1_900_000L)
        service.onStart(stubOwner)

        assertEquals(2, storage.getDeviceSessionCount())
    }

    @Test
    fun `session id changes when foreground after more than 30min`() {
        service.initialize(storage, npsManager)
        val firstSessionId = storage.getSessionId()

        service.onStop(stubOwner)
        backdateLastPause(1_900_000L)
        service.onStart(stubOwner)

        val secondSessionId = storage.getSessionId()
        assertNotNull(secondSessionId)
        assertNotEquals(firstSessionId, secondSessionId)
    }

    @Test
    fun `first seen at is not overwritten on subsequent sessions`() {
        service.initialize(storage, npsManager)
        val firstSeenAt = storage.getDeviceFirstSeenAt()

        service.onStop(stubOwner)
        backdateLastPause(1_900_000L)
        service.onStart(stubOwner)

        assertEquals(firstSeenAt, storage.getDeviceFirstSeenAt())
    }

    @Test
    fun `multiple long backgrounds accumulate session count`() {
        service.initialize(storage, npsManager)

        repeat(3) {
            service.onStop(stubOwner)
            backdateLastPause(1_900_000L)
            service.onStart(stubOwner)
        }

        assertEquals(4, storage.getDeviceSessionCount())
    }

    // ── Foreground After Short Background (<30 min) ────────────────────────────────

    @Test
    fun `foreground within 30min does not create new session`() {
        service.initialize(storage, npsManager)

        service.onStop(stubOwner)
        // pausedAtMs is set to ~now, so elapsed time is <30 s
        service.onStart(stubOwner)

        assertEquals(1, storage.getDeviceSessionCount())
    }

    @Test
    fun `session id stays the same after short background`() {
        service.initialize(storage, npsManager)
        val firstSessionId = storage.getSessionId()

        service.onStop(stubOwner)
        service.onStart(stubOwner)

        assertEquals(firstSessionId, storage.getSessionId())
    }

    // ── Dispose & Re-initialize ──────────────────────────────────────────────────

    @Test
    fun `dispose and reinitialize starts a new session`() {
        service.initialize(storage, npsManager)
        assertEquals(1, storage.getDeviceSessionCount())

        service.dispose()
        service.initialize(storage, npsManager)

        assertEquals(2, storage.getDeviceSessionCount())
    }

    @Test
    fun `dispose and reinitialize generates a new session id`() {
        service.initialize(storage, npsManager)
        val firstSessionId = storage.getSessionId()

        service.dispose()
        service.initialize(storage, npsManager)

        assertNotEquals(firstSessionId, storage.getSessionId())
    }

    // ── getSessionId ────────────────────────────────────────────────────────────

    @Test
    fun `getSessionId returns the session id set during initialize`() {
        service.initialize(storage, npsManager)

        val fromStorage = storage.getSessionId()
        val fromService = service.getSessionId()

        assertNotNull(fromStorage)
        assertEquals(fromStorage, fromService)
    }

    @Test
    fun `getSessionId provides a fallback id when service is uninitialized`() {
        // service has not been initialized — storage reference inside service is null
        val sessionId = service.getSessionId()

        assertNotNull(sessionId)
        assertTrue(sessionId.isNotEmpty())
    }

    @Test
    fun `getSessionId returns stable fallback id before initialization`() {
        // Repeated pre-init calls must return the same UUID
        val first = service.getSessionId()
        val second = service.getSessionId()

        assertEquals(first, second)
    }

    // ── Guard Against Calls Before Initialize ───────────────────────────────────

    @Test
    fun `onStart before initialize does not increment session count`() {
        // onStart checks initialized flag and returns early
        service.onStart(stubOwner)

        assertEquals(0, storage.getDeviceSessionCount())
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Backdates the last pause timestamp so that the elapsed time since
     * [SessionService.onStop] appears to be [elapsedMs] milliseconds.
     * Uses the [SessionService.setLastPauseMs] test hook instead of reflection.
     */
    private fun backdateLastPause(elapsedMs: Long) {
        service.setLastPauseMs(System.currentTimeMillis() - elapsedMs)
    }
}
