package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.data.AppDatabase
import com.example.data.HistoryRepository
import com.example.data.HistoryStatus
import com.example.data.HistoryType
import com.example.data.SettingsStore
import com.example.domain.DownloadUseCase
import com.example.network.HttpClients
import com.example.network.TelegramClient
import com.example.network.TikWmClient
import com.example.network.TikWmResult
import com.example.util.AlertSound
import com.example.util.HapticFeedback
import com.example.util.HtmlEscape
import com.example.util.LocaleHelper
import com.example.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

private const val TAG = "DownloadWorker"

/** Telegram Bot API hard limit for `sendVideo`. */
private const val MAX_TELEGRAM_BYTES = 50L * 1024 * 1024
private const val TELEGRAM_CAPTION_LIMIT = 1024
private const val TELEGRAM_TEXT_LIMIT = 4096
private const val ALBUM_SIZE = 10

class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val httpClient: OkHttpClient by lazy { HttpClients.shared }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val entryId = inputData.getLong(KEY_ENTRY_ID, -1L)
        if (entryId == -1L) return@withContext Result.failure()

        val repository = HistoryRepository(AppDatabase.getDatabase(applicationContext).historyDao())
        val settings = SettingsStore(applicationContext)

        LocaleHelper.applyLocale(applicationContext, settings.getLanguage())

        val token = settings.getBotToken()
        val groupId = settings.getGroupId()
        if (token.isEmpty() || groupId.isEmpty()) {
            fail(repository, entryId, applicationContext.getString(R.string.worker_error_missing_creds))
            return@withContext Result.failure()
        }

        setForeground(createForegroundInfo(entryId, applicationContext.getString(R.string.worker_preparing), 0f))
        repository.markRunning(entryId, countAttempt = false)

        val tikWmClient = TikWmClient(httpClient)
        val downloader = DownloadUseCase(applicationContext, httpClient)
        val telegram = TelegramClient(httpClient)
        val scope = CoroutineScope(coroutineContext)
        val reporter = ProgressReporter(scope) { progress, status ->
            setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to status))
            runCatching {
                setForeground(createForegroundInfo(entryId, status, progress))
            }
        }

        HapticFeedback.trigger(applicationContext, HapticFeedback.Type.START)

        try {
            reporter.report(0f, applicationContext.getString(R.string.worker_api), force = true)
            val media = tikWmClient.fetchMedia(url)

            val title = HtmlEscape.escapeAndClamp(media.title, 900)
            val header = buildString {
                append("📎 <b>").append(title).append("</b>")
                append("\n🔗 ").append(url)
            }
            val caption: String?
            val extraMessages: List<String>
            if (header.length <= TELEGRAM_CAPTION_LIMIT) {
                caption = header
                extraMessages = emptyList()
            } else {
                caption = "🔗 $url"
                extraMessages = header.chunked(TELEGRAM_TEXT_LIMIT)
            }

            val thumbnailDeferred = scope.async { media.coverUrl?.let { downloader.saveThumbnail(it) } }

            val outcome = when (media) {
                is TikWmResult.Video -> {
                    val file = downloadVideo(downloader, reporter, media)
                    reporter.report(0.97f, applicationContext.getString(R.string.worker_sending_video), force = true)
                    currentCoroutineContext().ensureActive()
                    val link = telegram.sendVideo(token, groupId, file, caption)
                    Outcome(
                        link = link,
                        type = HistoryType.VIDEO,
                        mediaCount = 0,
                        extraMessages = extraMessages,
                        replyTo = link.substringAfterLast('/').toLongOrNull()
                    )
                }

                is TikWmResult.Carousel -> {
                    val files = downloadCarousel(downloader, reporter, media)
                    reporter.report(0.97f, applicationContext.getString(R.string.worker_sending_carousel), force = true)
                    var firstMessageId: Long? = null
                    files.chunked(ALBUM_SIZE).forEachIndexed { index, chunk ->
                        currentCoroutineContext().ensureActive()
                        val messageId = telegram.sendMediaGroup(
                            token = token,
                            groupId = groupId,
                            files = chunk,
                            caption = if (index == 0) caption else null,
                            replyToMessageId = firstMessageId
                        )
                        if (index == 0) firstMessageId = messageId
                    }
                    Outcome(
                        link = telegram.formatMessageLink(groupId, firstMessageId!!),
                        type = HistoryType.CAROUSEL,
                        mediaCount = files.size,
                        extraMessages = extraMessages,
                        replyTo = firstMessageId
                    )
                }
            }

            val thumbnailPath = runCatching { thumbnailDeferred.await() }.getOrNull()
            repository.markDone(
                id = entryId,
                type = outcome.type,
                messageLink = outcome.link,
                mediaCount = outcome.mediaCount,
                thumbnailPath = thumbnailPath
            )
            sendExtraMessages(telegram, token, groupId, outcome)
            setForeground(createForegroundInfo(entryId, applicationContext.getString(R.string.worker_done), 1f))

            runCatching { HapticFeedback.trigger(applicationContext, HapticFeedback.Type.SUCCESS) }
            runCatching {
                NotificationHelper.showSuccessNotification(
                    context = applicationContext,
                    title = applicationContext.getString(R.string.worker_notif_success_title),
                    message = if (outcome.type == HistoryType.VIDEO) {
                        applicationContext.getString(R.string.worker_notif_video_success)
                    } else {
                        applicationContext.getString(R.string.worker_notif_carousel_success, outcome.mediaCount)
                    },
                    sound = AlertSound.parse(settings.getSuccessSound()),
                    volume = settings.getSuccessSoundVolume()
                )
            }
            runCatching { downloader.performAutoCleanup(settings.getCacheLimitMb(), settings.getCacheExpirationDays()) }

            Result.success()
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled: ${inputData.getString(KEY_URL)}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download error $url", e)
            fail(repository, entryId, humanMessage(e))
            Result.failure()
        } finally {
            runCatching { downloader.releaseAll() }
        }
    }

    private data class Outcome(
        val link: String,
        val type: String,
        val mediaCount: Int,
        val extraMessages: List<String>,
        val replyTo: Long?
    )

    private suspend fun downloadVideo(
        downloader: DownloadUseCase,
        reporter: ProgressReporter,
        media: TikWmResult.Video
    ): File {
        var file = downloader.downloadFile(
            media.videoUrl,
            "video.mp4",
            reporter.forItem(0, 1, applicationContext.getString(R.string.worker_downloading_video))
        )

        if (file.length() > MAX_TELEGRAM_BYTES && media.fallbackVideoUrl != null) {
            reporter.report(0f, applicationContext.getString(R.string.worker_video_too_large), force = true)
            file.delete()
            file = downloader.downloadFile(
                media.fallbackVideoUrl,
                "video_fallback.mp4",
                reporter.forItem(0, 1, applicationContext.getString(R.string.worker_downloading_video_fallback))
            )
        }

        if (file.length() > MAX_TELEGRAM_BYTES) {
            val sizeMb = file.length() / 1024 / 1024
            file.delete()
            throw IOException(applicationContext.getString(R.string.worker_error_video_limit, sizeMb))
        }
        return file
    }

    private suspend fun downloadCarousel(
        downloader: DownloadUseCase,
        reporter: ProgressReporter,
        media: TikWmResult.Carousel
    ): List<File> {
        val total = media.imageUrls.size
        return media.imageUrls.mapIndexed { index, imageUrl ->
            downloader.downloadFile(
                imageUrl,
                "img_$index.jpg",
                reporter.forItem(index, total, applicationContext.getString(R.string.worker_downloading_photo, index + 1, total))
            )
        }
    }

    private suspend fun sendExtraMessages(
        telegram: TelegramClient,
        token: String,
        groupId: String,
        outcome: Outcome
    ) {
        outcome.extraMessages.forEach { message ->
            runCatching { telegram.sendMessage(token, groupId, message, replyToMessageId = outcome.replyTo) }
                .onFailure { Log.w(TAG, "Failed to send extra message: ${it.message}") }
        }
    }

    private suspend fun fail(repository: HistoryRepository, entryId: Long, message: String) {
        runCatching { repository.markFailed(entryId, message) }
            .onFailure { Log.e(TAG, "Failed to record error to history", it) }
        runCatching {
            NotificationHelper.showErrorNotification(
                applicationContext,
                applicationContext.getString(R.string.worker_notif_error_title),
                message
            )
        }
    }

    private fun humanMessage(e: Exception): String =
        e.localizedMessage?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.worker_unknown_error)

    private fun createForegroundInfo(entryId: Long, status: String, progress: Float): ForegroundInfo =
        NotificationHelper.createProgressNotification(
            context = applicationContext,
            notificationId = progressNotificationId(entryId),
            status = status,
            progress = progress,
            indeterminate = progress <= 0f
        )

    private class ProgressReporter(
        private val scope: CoroutineScope,
        private val emit: suspend (Float, String) -> Unit
    ) {
        @Volatile
        private var lastEmitAt = 0L

        suspend fun report(progress: Float, status: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAt < PROGRESS_INTERVAL_MS) return
            lastEmitAt = now
            emit(progress.coerceIn(0f, 1f), status)
        }

        fun forItem(index: Int, total: Int, status: String) = object : DownloadUseCase.ProgressListener {
            override fun onProgress(percentage: Float) {
                val now = System.currentTimeMillis()
                if (now - lastEmitAt < PROGRESS_INTERVAL_MS) return
                scope.launch { report((index + percentage) / total.coerceAtLeast(1), status) }
            }
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_ENTRY_ID = "entryId"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val TAG_DOWNLOAD = "download_media"
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val ENTRY_TAG_PREFIX = "entry_"

        fun uniqueWorkName(entryId: Long) = "download_entry_$entryId"
        fun entryTag(entryId: Long) = "$ENTRY_TAG_PREFIX$entryId"
        const val ENTRY_TAG_PREFIX_STRING = ENTRY_TAG_PREFIX

        fun progressNotificationId(entryId: Long): Int =
            PROGRESS_NOTIFICATION_BASE + (entryId % PROGRESS_NOTIFICATION_SLOTS).toInt()

        private const val PROGRESS_NOTIFICATION_BASE = 1000
        private const val PROGRESS_NOTIFICATION_SLOTS = 500
    }
}
