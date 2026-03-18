package ai.releva.sdk.services.nps

import ai.releva.sdk.types.response.NpsConfig
import ai.releva.sdk.types.response.NpsTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NpsManagerServiceTest {

    private lateinit var service: NpsManagerService

    @Before
    fun setUp() {
        service = NpsManagerService()
    }

    @Test
    fun `initialize with no customEvent triggers fires immediately`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-1",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "appOpen")),
            triggerDelaySeconds = 0
        )
        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()

        assertEquals(1, emitted.size)
        assertEquals("nps-1", emitted[0].token)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `initialize with customEvent trigger waits for trackEvent`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-2",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "customEvent", eventName = "purchase")),
            triggerDelaySeconds = 0
        )
        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()

        // Not yet fired
        assertEquals(0, emitted.size)

        // Fire the matching event
        service.trackEvent("purchase")
        ShadowLooper.runMainLooperToNextTask()

        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `trackEvent with non-matching event does not trigger`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-3",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "customEvent", eventName = "purchase")),
            triggerDelaySeconds = 0
        )
        service.initialize(config)
        service.trackEvent("wrong_event")
        ShadowLooper.runMainLooperToNextTask()

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `cancelOnEvents suppresses NPS for session`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-4",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "customEvent", eventName = "purchase")),
            cancelOnEvents = listOf("checkout_started"),
            triggerDelaySeconds = 0
        )
        service.initialize(config)

        // Cancel event first
        service.trackEvent("checkout_started")
        // Then the trigger event
        service.trackEvent("purchase")
        ShadowLooper.runMainLooperToNextTask()

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `session suppression prevents double show`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-5",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "appOpen")),
            triggerDelaySeconds = 0
        )

        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()
        assertEquals(1, emitted.size)

        // Re-initialize should NOT show again (suppressed this session)
        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()
        assertEquals(1, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `startNewSession resets suppression`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-6",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "appOpen")),
            triggerDelaySeconds = 0
        )

        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()
        assertEquals(1, emitted.size)

        // Start new session and re-initialize
        service.startNewSession()
        service.initialize(config)
        ShadowLooper.runMainLooperToNextTask()
        assertEquals(2, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `initialize with null config does nothing`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        service.initialize(null)
        ShadowLooper.runMainLooperToNextTask()

        assertEquals(0, emitted.size)

        service.dispose()
        job.cancel()
    }

    @Test
    fun `trackEvent without config does nothing`() {
        // Should not crash
        service.trackEvent("anything")
    }

    @Test
    fun `dispose cancels pending timer`() = runTest {
        val emitted = mutableListOf<NpsConfig>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            NpsDisplayController.npsFlow.collect { emitted.add(it) }
        }

        val config = NpsConfig(
            token = "nps-7",
            question = "Rate us?",
            triggers = listOf(NpsTrigger(type = "appOpen")),
            triggerDelaySeconds = 60  // Long delay
        )

        service.initialize(config)
        service.dispose()
        ShadowLooper.runMainLooperToNextTask()

        // Timer was cancelled, nothing emitted
        assertEquals(0, emitted.size)

        job.cancel()
    }
}
