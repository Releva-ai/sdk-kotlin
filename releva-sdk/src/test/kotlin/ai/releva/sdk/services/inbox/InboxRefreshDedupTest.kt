package ai.releva.sdk.services.inbox

import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single cold open asks for a refresh from more than one place — the process lifecycle
 * observer and the host screen both call refreshIfStale, and a deep link to a message
 * adds a third. Each used to start its own pair of requests: six calls on one open where
 * two would do, all writing the same state, last one winning.
 *
 * The gate is what makes this deterministic. Without something holding the first fetch
 * open, the calls complete one after another and never overlap, so the test would pass
 * against the un-deduplicated code and prove nothing.
 */
// runTest's virtual clock cannot be used to bound these waits: InboxService refreshes on
// its own Dispatchers.IO scope, so the work runs on real threads while virtual time jumps
// straight to any deadline. The gate below is what bounds the test instead — nothing here
// waits on a timer.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboxRefreshDedupTest {

    private lateinit var context: Context
    private lateinit var storage: StorageService
    private lateinit var client: GatedInboxApiClient

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageService.getInstance(context)
        storage.clear()
        // InboxService.instance is a process-lifetime singleton. Without resetting it, a
        // previous test's client/state leaks in here (initialize() is a no-op for an already
        // initialized instance beyond swapping the client), and this test's own client/state
        // leaks into whichever test runs next — order-dependent flakiness either way.
        InboxService.instance.resetForTest()
        client = GatedInboxApiClient()
        InboxService.instance.initialize(client, storage)
    }

    @After
    fun tearDown() {
        InboxService.instance.resetForTest()
        storage.clear()
    }

    @Test
    fun `concurrent refreshes issue one fetch`() = runTest {
        val waiters = (1..3).map { async { InboxService.instance.refresh() } }

        client.awaitFirstCall()
        client.release()
        waiters.awaitAll()

        assertEquals("three overlapping refreshes should fetch once", 1, client.messageCalls.get())
        assertEquals("and read the unread count once", 1, client.countCalls.get())
    }

    @Test
    fun `every caller sees the refreshed state, not just the one that started it`() = runTest {
        client.unreadCount = 7
        val waiters = (1..3).map { async { InboxService.instance.refresh() } }

        client.awaitFirstCall()
        client.release()
        waiters.awaitAll()

        // refresh() promises the state is fresh once it returns — openMessageById reads
        // state.value on the very next line. A joiner that returned early would break that.
        assertEquals(7, InboxService.instance.state.value.unreadCount)
    }

    @Test
    fun `a later refresh still fetches once the first has finished`() = runTest {
        client.release()
        InboxService.instance.refresh()
        InboxService.instance.refresh()

        // The control for the test above: a completed refresh must not be reused, or
        // deduplication would quietly turn into "only ever fetch once per process".
        assertEquals(2, client.messageCalls.get())
    }

    private class GatedInboxApiClient : InboxApiClient {
        val messageCalls = AtomicInteger(0)
        val countCalls = AtomicInteger(0)
        var unreadCount = 0

        private val gate = CompletableDeferred<Unit>()
        private val firstCall = CompletableDeferred<Unit>()

        /** Suspends until a fetch has actually started, so the race is real and not assumed. */
        suspend fun awaitFirstCall() = firstCall.await()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun inboxFetchMessages(limit: Int, cursor: String?): Map<String, Any?> {
            messageCalls.incrementAndGet()
            firstCall.complete(Unit)
            gate.await()
            return mapOf("messages" to emptyList<Map<String, Any?>>(), "nextCursor" to null)
        }

        override suspend fun inboxFetchUnreadCount(): Int {
            countCalls.incrementAndGet()
            gate.await()
            return unreadCount
        }

        override suspend fun inboxMarkAsRead(messageId: String) = Unit
        override suspend fun inboxMarkAllAsRead() = Unit
        override suspend fun inboxDeleteMessage(messageId: String) = Unit
        override suspend fun inboxTrackAction(messageId: String) = Unit
    }
}
