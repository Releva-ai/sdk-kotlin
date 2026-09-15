package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        storage.clear()
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
                "GET /api/v0/inbox/unread-count -> attempt 1 of 3 failed, retrying in 2000ms"
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
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setBody("still down")) }

        val failure = failureFrom(createClient())

        // Unchanged from before the retry loop existed: the exception the caller sees is the
        // one the last response produced, body and all.
        assertEquals("Unread count API error: 503 - still down", failure.message)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2000L, 2000L), waits)
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

        assertTrue("expected the transport failure itself: $failure", failure is IOException)
        assertEquals(listOf(1000L, 1000L), waits)
    }

    private fun retryLogLines(): List<String> =
        ShadowLog.getLogsForTag("RelevaClient").map { it.msg }.filter { it.contains("retrying") }

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
