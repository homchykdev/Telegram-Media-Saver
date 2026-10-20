package com.example.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Guards the fix for the duplicate-upload report.
 *
 * The regression these tests exist for: a non-idempotent `sendVideo` used to be
 * replayed up to four times whenever an `IOException` occurred — including a read
 * timeout *after* Telegram had already accepted and delivered the video — so the
 * same file landed in the group several times, and a row could end up reported as
 * failed for a video that was actually there.
 *
 * Robolectric is required because the client parses Telegram's JSON replies with
 * `org.json`, which is otherwise a non-functional stub in JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramClientRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TelegramClient
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        client = TelegramClient(http, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun mediaFile(): File {
        val file = File.createTempFile("retry_test", ".mp4")
        file.writeBytes(ByteArray(64 * 1024) { it.toByte() })
        file.deleteOnExit()
        return file
    }

    private fun okResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"result":{"message_id":42}}""")

    /**
     * A server that reads the entire body and then never answers.
     *
     * This is the exact shape of the production failure: Telegram has the video, the
     * client times out waiting for the response.
     */
    private fun silentAfterBody(): MockResponse = MockResponse()
        .setSocketPolicy(SocketPolicy.NO_RESPONSE)

    @Test
    fun `post-upload timeout is not replayed`() = runBlocking {
        server.enqueue(silentAfterBody())
        server.enqueue(okResponse())

        var thrown: Exception? = null
        try {
            client.sendVideo("123456:AAaaBBbbCCccDDddEEeeFFffGGggHHhhII", "-1001", mediaFile(), null)
        } catch (e: Exception) {
            thrown = e
        }

        // The whole point: a single upload attempt, not four.
        assertEquals(
            "Video must be uploaded at most once when the outcome is unknown",
            1,
            server.requestCount
        )
        assertTrue(
            "Expected an UploadOutcomeUnknownException, got ${thrown?.javaClass?.name}: ${thrown?.message}",
            thrown is UploadOutcomeUnknownException
        )
        assertTrue(
            "Message must tell the user to check the group, was: ${thrown?.message}",
            thrown!!.message!!.contains("проверьте группу")
        )
    }

    @Test
    fun `sendMessage is not replayed after the request was delivered`() = runBlocking {
        server.enqueue(silentAfterBody())
        server.enqueue(okResponse())

        val thrown = runCatching {
            client.sendMessage("123456:AAaaBBbbCCccDDddEEeeFFffGGggHHhhII", "-1001", "Длинный заголовок")
        }.exceptionOrNull()

        // sendMessage used to carry no transfer tracker, so a lost response produced
        // up to four identical caption messages in the group.
        assertEquals("A caption message must be sent at most once", 1, server.requestCount)
        assertTrue(
            "Expected an UploadOutcomeUnknownException, got ${thrown?.javaClass?.name}",
            thrown is UploadOutcomeUnknownException
        )
    }

    @Test
    fun `a connection failure is retried rather than treated as an unknown outcome`() = runBlocking {
        // Point the client at a port nothing is listening on, so the body genuinely
        // never reaches the wire and a replay is provably safe.
        val deadPort = server.port
        server.shutdown()
        // Give @After a live server to tear down regardless of how the assertions go.
        server = MockWebServer().also { it.start() }
        val offline = TelegramClient(http, "http://127.0.0.1:$deadPort")

        val thrown = runCatching {
            offline.sendMessage("123456:AAaaBBbbCCccDDddEEeeFFffGGggHHhhII", "-1001", "Привет")
        }.exceptionOrNull()

        assertTrue(
            "A request that never left the machine must not be reported as an unknown outcome, got ${thrown?.javaClass?.name}",
            thrown !is UploadOutcomeUnknownException
        )
        assertTrue("expected a plain IOException, got $thrown", thrown is java.io.IOException)
    }

    @Test
    fun `5xx is not replayed for a non-idempotent upload`() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(502)) }

        var thrown: Exception? = null
        try {
            client.sendVideo("123456:AAaaBBbbCCccDDddEEeeFFggHHhhIIjj", "-1001", mediaFile(), null)
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals(
            "A 5xx can arrive after the upload landed, so it must not be replayed",
            1,
            server.requestCount
        )
        assertTrue(thrown is TelegramApiException)
    }

    @Test
    fun `429 is retried because the request was provably rejected`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":0}}""")
        )
        server.enqueue(okResponse())

        val link = client.sendVideo(
            "123456:AAaaBBbbCCccDDddEEeeFFggHHhhIIjj",
            "-1001",
            mediaFile(),
            null
        )

        assertEquals(2, server.requestCount)
        assertEquals("https://t.me/c/1/42", link)
    }

    @Test
    fun `http 400 is reported once with Telegram's own description`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""")
        )

        var thrown: Exception? = null
        try {
            client.sendVideo("123456:AAaaBBbbCCccDDddEEeeFFggHHhhIIjj", "-1001", mediaFile(), null)
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals(1, server.requestCount)
        // Regression: the body used to be read twice, which threw
        // IllegalStateException("closed") and lost this description.
        assertTrue(
            "Telegram's description must survive, was: ${thrown?.message}",
            thrown is TelegramApiException && thrown!!.message!!.contains("chat not found")
        )
    }

    @Test
    fun `idempotent reads still retry 5xx`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"bot"}}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"result":{"id":-1001,"type":"supergroup"}}""")
        )

        val result = client.testConnection("123456:AAaaBBbbCCccDDddEEeeFFggHHhhIIjj", "-1001")

        assertTrue(result)
        // 2 failed getMe attempts + 1 successful getMe + 1 getChat.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `idempotent reads retry a timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"bot"}}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"result":{"id":-1001,"type":"supergroup"}}""")
        )

        assertTrue(client.testConnection("123456:AAaaBBbbCCccDDddEEeeFFggHHhhIIjj", "-1001"))
        assertEquals(3, server.requestCount)
    }
}