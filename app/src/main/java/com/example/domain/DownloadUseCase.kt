package com.example.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.network.executeAwait
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DownloadUseCase"
private const val CACHE_DIR_NAME = "tiktok_dl"
private const val THUMBS_DIR_NAME = "thumbs"

class DownloadUseCase(
    private val context: Context,
    private val client: OkHttpClient
) {

    interface ProgressListener {
        fun onProgress(percentage: Float)
    }

    /** Records paths a transfer is writing to, or still needs for its upload. */
    private fun claimInFlight(vararg paths: File) {
        inFlight += paths.map { it.absolutePath }
    }

    private fun releaseInFlight(vararg paths: File) {
        inFlight -= paths.map { it.absolutePath }
    }

    /**
     * Internal rather than private so the sweeper's central decision can be asserted
     * directly: the on-disk effect alone cannot distinguish "protected" from "the
     * host filesystem refused to unlink an open file", which is exactly the case
     * these guards exist for.
     */
    internal fun isInFlight(file: File): Boolean {
        var current: File? = file
        while (current != null) {
            if (current.absolutePath in inFlight) return true
            current = current.parentFile
        }
        return false
    }

    /**
     * Streams [url] into a fresh cache directory.
     *
     * Suspending on purpose: the transfer runs through [Call.executeAwait] so that
     * cancelling the coroutine tears the socket down instead of leaving a blocking
     * read running to completion. That is what allows a replaced or cancelled
     * download to actually stop rather than finish and upload anyway.
     *
     * The partial file is always removed on failure, otherwise an aborted download
     * leaves a zero-or-half-length file behind that the cache sweeper then has to
     * deal with and that makes retry diagnostics misleading.
     */
    @Throws(IOException::class)
    suspend fun downloadFile(url: String, suffix: String, listener: ProgressListener? = null): File {
        val uniqueDir = File(context.cacheDir, "$CACHE_DIR_NAME/${UUID.randomUUID().toString().take(12)}")
        if (!uniqueDir.exists() && !uniqueDir.mkdirs()) {
            throw IOException("Не удалось создать временную папку для загрузки")
        }

        val destinationFile = File(uniqueDir, "media_$suffix")
        claimInFlight(uniqueDir, destinationFile)
        return try {
            // The claim is deliberately NOT released here on success: the file is
            // still needed for the upload, which happens after this returns.
            downloadInto(url, uniqueDir, destinationFile, listener)
        } catch (e: Throwable) {
            releaseInFlight(uniqueDir, destinationFile)
            throw e
        }
    }

    /**
     * Frees the claim taken by [downloadFile] once the media has been uploaded or
     * abandoned. The worker calls this in both cases; without it a claim would
     * leak and permanently protect that file from the cache sweeper.
     *
     * Only the paths this one download claimed (the file and its unique cache
     * directory) are touched. Two workers can run concurrently, so a blanket
     * "release everything" from one worker's `finally` would drop another worker's
     * still-active claims and let the cache sweeper unlink a file being downloaded
     * right now.
     */
    fun releaseDownloaded(file: File) {
        releaseInFlight(file, file.parentFile ?: return)
    }

    /**
     * Drops every claim held in this process. Tests and teardown-only: production
     * download paths must release their own files via [releaseDownloaded], because
     * claims taken by one concurrent transfer have no business being cleared by
     * another.
     */
    fun releaseAll() {
        inFlight.clear()
    }

    private suspend fun downloadInto(
        url: String,
        uniqueDir: File,
        destinationFile: File,
        listener: ProgressListener?
    ): File {
        val request = Request.Builder()
            .url(url)
            // The TikTok CDN rejects requests without a browser-ish UA with 403,
            // which surfaced as a bogus "Не удалось загрузить медиа" error.
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://www.tiktok.com/")
            .build()

        try {
            client.newCall(request).executeAwait().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Не удалось загрузить медиа по ссылке: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Тело ответа пустое")
                val contentLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytesRead = 0L
                        var lastReported = -1f

                        while (true) {
                            // The socket is torn down by executeAwait's cancellation
                            // handler, which makes the blocking read below return
                            // promptly; this check surfaces the cancellation as
                            // CancellationException instead of a confusing IOException.
                            currentCoroutineContext().ensureActive()

                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break

                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (listener != null) {
                                val pct = if (contentLength > 0) {
                                    totalBytesRead.toFloat() / contentLength
                                } else {
                                    -1f
                                }
                                if (pct < 0f || pct - lastReported >= PROGRESS_STEP) {
                                    lastReported = pct
                                    listener.onProgress(pct.coerceIn(0f, 1f))
                                }
                            }
                        }
                        outputStream.fd.sync()
                    }
                }
            }
        } catch (e: Throwable) {
            destinationFile.delete()
            uniqueDir.delete()
            throw e
        }

        if (destinationFile.length() == 0L) {
            destinationFile.delete()
            uniqueDir.delete()
            throw IOException("Скачанный файл пустой (0 байт)")
        }
        return destinationFile
    }

    suspend fun saveThumbnail(url: String): String? = runCatching {
        val thumbsDir = File(context.cacheDir, "$CACHE_DIR_NAME/$THUMBS_DIR_NAME")
        if (!thumbsDir.exists()) thumbsDir.mkdirs()

        val thumbFile = File(thumbsDir, "thumb_${UUID.randomUUID().toString().take(12)}.jpg")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://www.tiktok.com/")
            .build()

        client.newCall(request).executeAwait().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            // A cover image is a few hundred kilobytes; anything larger is not one, and
            // buffering it would be a memory spike for nothing.
            if (body.contentLength() > MAX_THUMB_BYTES) return@use null

            // The body is buffered once and both decode passes read from that buffer.
            // Decoding the same `byteStream()` twice cannot work: the first pass closes
            // it, and the second would decode a consumed stream into null — so the
            // thumbnail silently never existed.
            val bytes = body.bytes()
            if (bytes.isEmpty()) return@use null

            // Measure first so a large cover never lands in memory at full resolution
            // before being downscaled.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMB_MAX_PX * 2)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return@use null

            val scaled = scaleToFit(bitmap, THUMB_MAX_PX)
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            thumbFile.absolutePath
        }
    }.onFailure { Log.w(TAG, "Не удалось сохранить превью: ${it.message}") }.getOrNull()

    // ------------------------------------------------------------------ cache

    /**
     * Deletes a history entry's thumbnail. Thumbnails are unique per save, so the
     * path is not shared with other entries — the `isInFlight` guard only protects
     * against the unlikely case of a transfer still writing to it.
     */
    fun deleteThumbnail(path: String) {
        val file = File(path)
        if (!file.isFile || isInFlight(file)) return
        file.delete()
    }

    private fun cacheFolder(): File = File(context.cacheDir, CACHE_DIR_NAME)

    /**
     * Empties the download cache. Anything a running transfer still owns is kept:
     * deleting it mid-flight leaves the worker writing into an unlinked inode and
     * surfaces to the user as "Скачанный файл пустой".
     */
    fun clearTempDir(includeThumbs: Boolean = false) {
        val root = cacheFolder()
        if (!root.exists()) return
        if (includeThumbs) {
            root.listFiles()?.forEach { child ->
                if (isInFlight(child)) return@forEach
                child.deleteRecursively()
            }
        } else {
            root.listFiles()
                ?.filter { it.name != THUMBS_DIR_NAME && !isInFlight(it) }
                ?.forEach { it.deleteRecursively() }
        }
    }

    fun getCacheSize(): Long = folderSize(cacheFolder())

    private fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { size += folderSize(it) }
        return size
    }

    /**
     * Deletes expired files first, then trims the oldest files until the cache fits
     * the configured budget, then prunes the directories left empty.
     */
    fun performAutoCleanup(maxSizeMb: Int, expirationDays: Int) {
        val root = cacheFolder()
        if (!root.exists()) return

        val expirationTime = System.currentTimeMillis() - expirationDays * 24L * 60L * 60L * 1000L
        deleteExpired(root, expirationTime)

        val maxSizeBytes = maxSizeMb * 1024L * 1024L
        var currentSize = folderSize(root)
        if (currentSize > maxSizeBytes) {
            val allFiles = mutableListOf<File>()
            collectFiles(root, allFiles)
            allFiles.sortBy { it.lastModified() }
            for (file in allFiles) {
                if (currentSize <= maxSizeBytes) break
                if (isInFlight(file)) continue
                val fileSize = file.length()
                if (file.delete()) currentSize -= fileSize
            }
        }
        deleteEmptyDirs(root)
    }

    private fun deleteExpired(file: File, expirationTime: Long) {
        if (file.isFile) {
            if (!isInFlight(file) && file.lastModified() < expirationTime) file.delete()
        } else {
            file.listFiles()?.forEach { deleteExpired(it, expirationTime) }
        }
    }

    private fun collectFiles(file: File, into: MutableList<File>) {
        if (file.isFile) {
            into.add(file)
        } else {
            file.listFiles()?.forEach { collectFiles(it, into) }
        }
    }

    private fun deleteEmptyDirs(file: File) {
        if (!file.isDirectory) return
        file.listFiles()?.forEach { deleteEmptyDirs(it) }
        if (isInFlight(file)) return
        if (file.listFiles()?.isEmpty() == true && file.name != CACHE_DIR_NAME) file.delete()
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Largest power-of-two subsample that still leaves the image at least [target]
     * pixels on its *smaller* axis.
     *
     * The previous version required both axes to stay above the target, so a
     * 1080x1920 portrait cover (1080/2 = 540 < 640) sampled at 1 and decoded the
     * full-size bitmap before scaling it down — the two-pass decode saved nothing.
     */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun scaleToFit(source: Bitmap, maxSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source
        val ratio = width.toFloat() / height.toFloat()
        val (newWidth, newHeight) = if (width > height) {
            maxSize to (maxSize / ratio).toInt().coerceAtLeast(1)
        } else {
            (maxSize * ratio).toInt().coerceAtLeast(1) to maxSize
        }
        if (newWidth == width && newHeight == height) return source
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        const val PROGRESS_STEP = 0.01f
        const val THUMB_MAX_PX = 320
        const val THUMB_QUALITY = 80
        const val MAX_THUMB_BYTES = 8L * 1024 * 1024

        /**
         * Files and directories a transfer is currently writing to or still needs.
         *
         * The cache sweeper runs on every `onResume`, independently of any worker, so
         * it used to be able to unlink a file mid-download — the worker's open
         * descriptor kept working but the path vanished, producing "Скачанный файл
         * пустой" and, for carousels, deleting photos that were already fetched but
         * not yet uploaded.
         *
         * This is process-wide, and that detail is load-bearing: the instance that
         * *takes* a claim belongs to the worker, while the instance that *sweeps* the
         * cache belongs to the ViewModel. A per-instance set left the sweeper looking
         * at a permanently empty collection, so every guard below silently did
         * nothing.
         */
        val inFlight: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    }
}