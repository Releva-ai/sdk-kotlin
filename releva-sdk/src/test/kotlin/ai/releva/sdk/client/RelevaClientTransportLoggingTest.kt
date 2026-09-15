package ai.releva.sdk.client

import ai.releva.sdk.config.RelevaConfig
import ai.releva.sdk.services.storage.StorageService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/**
 * The request log's transport-failure case: a call that never gets an answer used to leave
 * no trace at all, which is the case an integrator most needs the log for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RelevaClientTransportLoggingTest {

    private lateinit var context: Context
    private lateinit var storage: StorageService

    /** A localhost URL nothing is listening on — see [setUp]. */
    private lateinit var deadEndpoint: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageService.getInstance(context)
        storage.clear()
        ShadowLog.reset()

        // A MockWebServer started and immediately shut down hands back a port that was free
        // and is free again, so the request fails to connect the way it does with no network
        // — without depending on the build machine having (or not having) one.
        val server = MockWebServer()
        server.start()
        deadEndpoint = server.url("/").toString().removeSuffix("/")
        server.shutdown()
    }

    @After
    fun tearDown() {
        storage.clear()
        ShadowLog.reset()
    }

    @Test
    fun `a request that never reaches the server is logged and rethrown`() = runTest {
        val client = createClient(RelevaConfig.full())
        client.setProfileId("profile-1")

        var failure: IOException? = null
        try {
            client.inboxFetchUnreadCount()
            fail("expected the transport failure to propagate to the caller")
        } catch (e: IOException) {
            failure = e
        }

        val logged = requestLines()
        assertEquals("expected exactly one request log line, got $logged", 1, logged.size)
        val line = logged.single()
        assertEquals(Log.WARN, line.type)
        assertTrue(
            "unexpected shape: ${line.msg}",
            Regex("""^GET /api/v0/inbox/unread-count -> failed in \d+ms: """).containsMatchIn(line.msg)
        )
        assertTrue(
            "exception class missing from: ${line.msg}",
            line.msg.contains(failure!!.javaClass.simpleName)
        )
    }

    @Test
    fun `the failure is not logged when request logging is disabled, and still propagates`() = runTest {
        val client = createClient(RelevaConfig.full().copy(enableRequestLogging = false))
        client.setProfileId("profile-1")

        var propagated = false
        try {
            client.inboxFetchUnreadCount()
            fail("expected the transport failure to propagate to the caller")
        } catch (e: IOException) {
            propagated = true
        }

        assertTrue(propagated)
        assertEquals(emptyList<String>(), requestLines().map { it.msg })
    }

    private fun requestLines(): List<ShadowLog.LogItem> =
        // setEndpointOverride warns about the http:// scheme under the same tag; the request
        // lines are the ones that start with the verb.
        ShadowLog.getLogsForTag("RelevaClient").filter { it.msg.startsWith("GET ") }

    private fun createClient(config: RelevaConfig): RelevaClient =
        RelevaClient(context, "", "test-token", config).apply { setEndpointOverride(deadEndpoint) }
}
