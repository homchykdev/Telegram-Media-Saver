package com.example.domain

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The cache sweeper runs on every `onResume`, on a scheduler the worker knows nothing
 * about. These tests pin the properties that keep it from corrupting a transfer: a
 * file an active download owns is never swept, and cancelling a download really does
 * stop it instead of leaving a blocking read running to completion.
 *
 * The claim is asserted through [DownloadUseCase.isInFlight] rather than inferred
 * from whether the file survived on disk. On the JVM test host an open file cannot be
 * unlinked, so a survival check alone cannot tell "the sweeper protected this" apart
 * from "the operating system said no" — and that second case is precisely the
 * situation the protection exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadUseCaseCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var useCase: DownloadUseCase
    private lateinit var useCaseClient: OkHttpClient

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun cacheRoot() = File(context.cacheDir, "tiktok_dl")
    private fun cacheFiles(): List<File> = cacheRoot().walkTopDown().filter { it.isFile }.toList()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        useCaseClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        useCase = DownloadUseCase(context, useCaseClient)
    }

    @After
    fun tearDown() {
        useCase.releaseAll()
        server.shutdown()
        useCase.clearTempDir(includeThumbs = true)
    }

    /** A body that is announced immediately but withheld, so the transfer is still running. */
    private fun slowBody(bytes: Int = 512 * 1024): MockResponse = MockResponse()
        .setBody(Buffer().write(ByteArray(bytes)))
        .setBodyDelay(30, TimeUnit.SECONDS)

    private fun immediateBody(bytes: Int = 4096): MockResponse =
        MockResponse().setBody(Buffer().write(ByteArray(bytes)))

    /** Waits for the transfer to actually create its destination file. */
    private suspend fun awaitCacheFile(): File = withTimeout(10_000) {
        var file: File? = null
        while (file == null) {
            file = cacheFiles().firstOrNull()
            if (file == null) delay(20)
        }
        file
    }

    private fun CoroutineScope.startSlowDownload(path: String = "/v.mp4") =
        async(Dispatchers.IO) { useCase.downloadFile(server.url(path).toString(), "mp4") }

    private fun staleFile(name: String, dir: String = "stale"): File {
        val parent = File(cacheRoot(), dir).apply { mkdirs() }
        return File(parent, name).apply { writeBytes(ByteArray(64)) }
    }

    // ------------------------------------------------------------- the claim

    @Test
    fun `a running download claims its file and directory`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        val claimed = awaitCacheFile()

        assertTrue("the destination file is not claimed", useCase.isInFlight(claimed))
        assertTrue(
            "the containing directory is not claimed, so the sweeper could unlink it",
            useCase.isInFlight(claimed.parentFile!!)
        )
        job.cancel()
    }

    @Test
    fun `a claim is visible to a different instance`() = runBlocking {
        server.enqueue(slowBody())
        // The worker that *takes* the claim and the ViewModel instance that *sweeps*
        // the cache are never the same object. A per-instance claim set left the
        // sweeper looking at a permanently empty collection, so it deleted files out
        // from under a running transfer.
        val worker = DownloadUseCase(context, useCaseClient)
        val sweeper = DownloadUseCase(context, useCaseClient)

        val job = worker.let {
            async(Dispatchers.IO) { it.downloadFile(server.url("/v.mp4").toString(), "mp4") }
        }
        val claimed = awaitCacheFile()

        assertTrue("the sweeper's instance does not see the worker's claim", sweeper.isInFlight(claimed))
        sweeper.performAutoCleanup(maxSizeMb = 0, expirationDays = 3650)
        assertTrue("an in-flight file was swept by another instance", claimed.exists())
        job.cancel()
    }

    @Test
    fun `a claim does not extend to sibling download directories`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        awaitCacheFile()

        assertFalse("an unrelated cache entry was claimed", useCase.isInFlight(staleFile("other.mp4")))
        job.cancel()
    }

    @Test
    fun `a successful download keeps its claim until the worker releases it`() = runBlocking {
        server.enqueue(immediateBody())
        val file = useCase.downloadFile(server.url("/done.mp4").toString(), "mp4")

        // The upload happens after downloadFile returns, so the sweeper must still
        // stay away from the file at this point.
        assertTrue(useCase.isInFlight(file))

        useCase.releaseDownloaded(file)
        assertFalse(useCase.isInFlight(file))
    }

    @Test
    fun `a failed download drops its claim`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        runCatching { useCase.downloadFile(server.url("/x.mp4").toString(), "mp4") }

        // A claim that outlived its transfer would protect a path nobody can name
        // again, so the sweeper would silently stop working for the rest of the run.
        assertTrue("a failed download left files behind", cacheFiles().isEmpty())
        val probe = staleFile("probe.mp4", dir = "probe")
        useCase.performAutoCleanup(maxSizeMb = 0, expirationDays = 3650)
        assertFalse("a leaked claim disabled the sweeper", probe.exists())
    }

    @Test
    fun `cancelling a download releases its claim`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        val claimed = awaitCacheFile()
        assertTrue(useCase.isInFlight(claimed))

        job.cancel()
        runCatching { job.await() }

        assertFalse(
            "the claim survived a cancelled transfer and would blind the sweeper forever",
            useCase.isInFlight(claimed)
        )
    }

    // --------------------------------------------------------- the sweeper

    @Test
    fun `clearTempDir skips a claimed entry and deletes the rest`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        val claimed = awaitCacheFile()
        val unrelated = staleFile("unrelated.mp4")

        useCase.clearTempDir(includeThumbs = true)

        assertTrue(useCase.isInFlight(claimed))
        assertTrue("the in-flight file was deleted", claimed.exists())
        assertFalse("an unclaimed file was not deleted", unrelated.exists())
        job.cancel()
    }

    @Test
    fun `the expiry sweep skips a claimed file however old it is`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        val claimed = awaitCacheFile()
        assertTrue(claimed.setLastModified(0L))
        val expired = staleFile("expired.mp4").also { assertTrue(it.setLastModified(0L)) }

        useCase.performAutoCleanup(maxSizeMb = 512, expirationDays = 0)

        assertTrue(useCase.isInFlight(claimed))
        assertTrue("an expired in-flight file was deleted", claimed.exists())
        assertFalse("an expired unclaimed file survived", expired.exists())
        job.cancel()
    }

    @Test
    fun `the size cap skips claimed files and trims the rest`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        val claimed = awaitCacheFile()
        val bulk = (1..8).map { staleFile("bulk$it.mp4", dir = "bulk") }

        useCase.performAutoCleanup(maxSizeMb = 0, expirationDays = 3650)

        assertTrue(useCase.isInFlight(claimed))
        assertTrue("an in-flight file was trimmed to satisfy the size cap", claimed.exists())
        assertTrue("unclaimed files were not trimmed", bulk.none { it.exists() })
        job.cancel()
    }

    @Test
    fun `concurrent downloads do not sweep each other`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = slowBody()
        }

        val first = startSlowDownload("/a.mp4")
        val second = startSlowDownload("/b.mp4")

        withTimeout(10_000) { while (cacheFiles().size < 2) delay(20) }
        val claimed = cacheFiles()
        assertEquals(2, claimed.size)

        useCase.performAutoCleanup(maxSizeMb = 0, expirationDays = 3650)

        assertTrue("one of the two in-flight downloads was swept", claimed.all { it.exists() })
        first.cancel()
        second.cancel()
    }

    @Test
    fun `a completed download is swept once the worker releases it`() = runBlocking {
        server.enqueue(immediateBody())
        val file = useCase.downloadFile(server.url("/done.mp4").toString(), "mp4")
        assertTrue(file.setLastModified(0L))

        useCase.performAutoCleanup(maxSizeMb = 512, expirationDays = 0)
        assertTrue("a claimed file was expired out from under the upload", file.exists())

        useCase.releaseDownloaded(file)
        useCase.performAutoCleanup(maxSizeMb = 512, expirationDays = 0)
        assertFalse("a released file was never swept", file.exists())
    }

    @Test
    fun `releaseAll drops every claim this instance holds`() = runBlocking {
        server.enqueue(immediateBody())
        server.enqueue(immediateBody())
        val a = useCase.downloadFile(server.url("/a.mp4").toString(), "mp4")
        val b = useCase.downloadFile(server.url("/b.mp4").toString(), "mp4")
        assertTrue(useCase.isInFlight(a) && useCase.isInFlight(b))

        useCase.releaseAll()

        assertFalse(useCase.isInFlight(a))
        assertFalse(useCase.isInFlight(b))
    }

    // ------------------------------------------------------- transfer errors

    @Test
    fun `cancelling a download stops it and leaves nothing behind`() = runBlocking {
        server.enqueue(slowBody())
        val job = startSlowDownload()
        awaitCacheFile()

        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()

        assertTrue(
            "expected the cancellation to surface, got $failure",
            failure is CancellationException
        )
        withContext(Dispatchers.IO) { delay(200) }
        assertTrue("a partial download was left in the cache", cacheFiles().isEmpty())
    }

    @Test
    fun `a 404 leaves no partial file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val failure = runCatching {
            useCase.downloadFile(server.url("/missing.mp4").toString(), "mp4")
        }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("404"))
        assertTrue("a failed download left files behind", cacheFiles().isEmpty())
    }

    @Test
    fun `a 500 leaves no partial file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val failure = runCatching {
            useCase.downloadFile(server.url("/boom.mp4").toString(), "mp4")
        }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(cacheFiles().isEmpty())
    }

    @Test
    fun `an empty body is rejected instead of uploaded as a zero byte file`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        val failure = runCatching {
            useCase.downloadFile(server.url("/empty.mp4").toString(), "mp4")
        }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("0"))
        assertTrue(cacheFiles().isEmpty())
    }
}