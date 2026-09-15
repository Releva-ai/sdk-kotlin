package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.session.SessionService
import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/**
 * `RelevaClient.execute`'s retry policy. Driven through `inboxFetchUnreadCount` because it is
 * the shortest path to `execute` that reports both the status and the body back to the test;
 * the policy itself is per-request and verb-agnostic.
 *
 * Every test enqueues one more response than it expects to be consumed, so an implementation
 * that retries when it should not fails on the request count instead of blocking on an empty
 * MockWebServer queue until the coroutine test times out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelevaClientRetryTest {

    private lateinit var context: Context
    private lateinit var storage: StorageService
    private lateinit var server: MockWebServer

    /** The waits the client asked for, in order, instead of the seconds it asked to spend. */
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageService.getInstance(context)
        storage.clear()
        storage.setProfileId("profile-1")
        waits.clear()
        ShadowLog.reset()
        // submitNpsResponse's tests below go through SessionService, a process-wide singleton
        // that only initializes once; dispose() resets it so each test starts cold.
        SessionService.getInstance().dispose()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        storage.clear()
        SessionService.getInstance().dispose()
        ShadowLog.reset()
    }

    @Test
    fun `a 500 is retried and the caller gets the answer to the second attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":7}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":0}"""))

        assertEquals(7, createClient().inboxFetchUnreadCount())

        assertEquals(2, server.requestCount)
        assertEquals(listOf(2000L), waits)
        assertTrue(
            "no retry line among ${retryLogLines()}",
            retryLogLines().contains(
                "GET /api/v0/inbox/unread-count -> attempt 1 of 4 failed, retrying in 2000ms"
            )
        )
    }

    @Test
    fun `a 4xx reaches the caller after one request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such profile"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":7}"""))

        val failure = failureFrom(createClient())

        assertEquals("Unread count API error: 404", failure.message)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `a 2xx reaches the caller after one request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":3}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":0}"""))

        assertEquals(3, createClient().inboxFetchUnreadCount())

        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `the last 500 reaches the caller once the attempts are spent`() = runTest {
        // maxRetryAttempts is retries on top of the first try, so the default of 3 spends 4
        // requests total; one more than that enqueued so an over-eager implementation fails on
        // the request count instead of hanging on an empty queue.
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503).setBody("still down")) }

        val failure = failureFrom(createClient())

        // Unchanged from before the retry loop existed: the exception the caller sees is the
        // one the last response produced, body and all.
        assertEquals("Unread count API error: 503 - still down", failure.message)
        assertEquals(4, server.requestCount)
        assertEquals(listOf(2000L, 2000L, 2000L), waits)
    }

    @Test
    fun `maxRetryAttempts of 0 makes exactly one request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":7}"""))

        val failure = failureFrom(createClient(RelevaConfig.full().copy(maxRetryAttempts = 0)))

        assertEquals("Unread count API error: 500 - upstream unavailable", failure.message)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `a request that never reaches a server is retried too, and waits less`() = runTest {
        val client = createClient()
        client.setEndpointOverride(deadEndpoint())

        val failure = failureFrom(client)

        // Default maxRetryAttempts = 3 retries on top of the first try = 4 requests, so 3
        // waits precede the exception that finally reaches the caller.
        assertTrue("expected the transport failure itself: $failure", failure is IOException)
        assertEquals(listOf(1000L, 1000L, 1000L), waits)
    }

    @Test
    fun `a body read failure after a response arrives is not retried`() = runTest {
        // The server has already sent a status line and headers before the connection drops
        // mid-body — a materially different case from "never reached the server", since the
        // server already committed to this status. A second enqueued response would only be
        // consumed if execute() wrongly retried this path.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"count":7}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"count":0}"""))

        val failure = failureFrom(createClient())

        assertTrue("expected an IOException from the failed body read: $failure", failure is IOException)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)

        // Not retried, but not silent either. The body read sits outside the retry `try`, and
        // when the merge with the transport-logging fix put it there it also put it outside
        // any catch — so this failure reached the caller with nothing in the log, which is the
        // one outcome that log exists to prevent. Since OkHttp returns at the response
        // headers, this is where an ordinary mid-transfer disconnect lands.
        assertEquals("expected one failure line, got ${failureLogLines()}", 1, failureLogLines().size)
        assertTrue(
            "unexpected shape: ${failureLogLines().single()}",
            Regex("""^GET /api/v0/inbox/unread-count -> failed in \d+ms: """)
                .containsMatchIn(failureLogLines().single())
        )
    }

    // submitNpsResponse used to wrap its own call in a one-shot retry on top of whatever
    // execute() did. That layer is gone: NPS submission now relies solely on execute()'s loop,
    // same policy as every other endpoint. submitNpsResponse swallows every failure itself
    // (the thank-you screen shows regardless), so these assert the only thing observable from
    // outside — how many requests reached the server — rather than an exception.

    @Test
    fun `submitNpsResponse does not retry a 4xx, one request total`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("no such survey"))
        server.enqueue(MockResponse().setResponseCode(202))

        createClient(RelevaConfig.full().copy(maxRetryAttempts = 1))
            .submitNpsResponse("survey-token", score = 8)

        // Old behaviour (the removed caller-level retry) issued 2 requests here regardless of
        // maxRetryAttempts, since it retried on any non-202 independently of execute()'s budget.
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `submitNpsResponse retries a 5xx up to the default budget, four requests`() = runTest {
        // Default maxRetryAttempts = 3 retries on top of the first try = 4 requests total; one
        // more 500 enqueued than that so an over-eager implementation fails on the count.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("upstream unavailable")) }
        server.enqueue(MockResponse().setResponseCode(202))

        createClient().submitNpsResponse("survey-token", score = 3)

        // Old behaviour: 1 initial request + 1 caller-level retry = 2, since execute() itself
        // had no loop yet. New behaviour: execute()'s loop alone carries it to the default
        // budget (3 retries, 4 requests total), with no doubling from a second retry layer.
        assertEquals(4, server.requestCount)
        assertEquals(listOf(2000L, 2000L, 2000L), waits)
    }

    private fun retryLogLines(): List<String> =
        ShadowLog.getLogsForTag("RelevaClient").map { it.msg }.filter { it.contains("retrying") }

    /**
     * The per-attempt failure lines, which are a different line from [retryLogLines]: that one
     * matches `retryAfter`'s "attempt N of M failed, retrying" and names the attempt but not
     * the cause, and it is not written at all for the attempt that finally throws.
     */
    private fun failureLogLines(): List<String> =
        ShadowLog.getLogsForTag("RelevaClient").map { it.msg }.filter { it.contains("-> failed in ") }

    /**
     * A localhost URL nothing is listening on — a server started and immediately shut down
     * hands back a port that was free and is free again, so the connection is refused the way
     * it is with no network, without depending on the build machine having one.
     */
    private fun deadEndpoint(): String {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString().removeSuffix("/")
        dead.shutdown()
        return url
    }

    private suspend fun failureFrom(client: RelevaClient): Exception =
        try {
            client.inboxFetchUnreadCount()
            // An Error, so the catch below cannot swallow it.
            throw AssertionError("expected the failure to reach the caller")
        } catch (e: Exception) {
            e
        }

    private fun createClient(config: RelevaConfig = RelevaConfig.full()): RelevaClient =
        RelevaClient(context, "", "test-token", config).apply {
            setEndpointOverride(server.url("/").toString().removeSuffix("/"))
            retryWait = { waits += it }
        }
}
