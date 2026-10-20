package com.example.worker

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
import java.util.concurrent.TimeUnit

private const val TAG = "DownloadWorker"

/** Telegram Bot API hard limit for `sendVideo`. */
private const val MAX_TELEGRAM_BYTES = 50L * 1024 * 1024
private const val TELEGRAM_CAPTION_LIMIT = 1024
private const val TELEGRAM_TEXT_LIMIT = 4096
private const val ALBUM_SIZE = 10

/**
 * Downloads a shared link and uploads it to Telegram.
 *
 * Reliability notes, each of these was a real production failure:
 *  - The worker runs as a **foreground service**. Without this, Android stops the
 *    job mid-download on long transfers and the coroutine is cancelled, which the
 *    old code caught and reported as the literal message "Job cancelled".
 *  - [CancellationException] is rethrown instead of being swallowed. Catching it
 *    and writing an error row is what made a user-cancelled job look like a crash.
 *  - The history row is marked **done immediately after Telegram confirms the
 *    upload**, before any haptic/notification/cleanup work. Previously a failure in
 *    that trailing code overwrote a successful result with "error", so the app
 *    reported a failure for videos that were already in the group.
 *  - Progress is published at most every [PROGRESS_INTERVAL_MS], not per 8 KB read.
 */
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

        val token = settings.getBotToken()
        val groupId = settings.getGroupId()
        if (token.isEmpty() || groupId.isEmpty()) {
            fail(repository, entryId, "Не заполнены токен бота или Group ID в настройках")
            return@withContext Result.failure()
        }

        setForeground(createForegroundInfo(entryId, "Подготовка…", 0f))
        // countAttempt=false: MainViewModel.startDownload already counted this run,
        // so incrementing here too inflated the counter to 2 per download.
        repository.markRunning(entryId, countAttempt = false)

        val tikWmClient = TikWmClient(httpClient)
        val downloader = DownloadUseCase(applicationContext, httpClient)
        val telegram = TelegramClient(httpClient)
        // A child of the worker's own scope, so a cancelled or replaced worker takes
        // its pending progress writes down with it. It is deliberately NOT given its
        // own Job: that would detach it, and cancelling it by hand would risk
        // cancelling the worker's coroutine itself.
        val scope = CoroutineScope(coroutineContext)
        val reporter = ProgressReporter(scope) { progress, status ->
            setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STATUS to status))
            // setProgress does not touch the notification, so without this the
            // ongoing notification kept reading "Подготовка…" for minutes.
            runCatching {
                setForeground(createForegroundInfo(entryId, status, progress))
            }
        }

        HapticFeedback.trigger(applicationContext, HapticFeedback.Type.START)

        try {
            reporter.report(0f, "Обращение к API TikWM…", force = true)
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
                // Keep the caption short so the video/photo is never rejected for length,
                // and push the full title as a follow-up reply.
                caption = "🔗 $url"
                extraMessages = header.chunked(TELEGRAM_TEXT_LIMIT)
            }

            // Fetched concurrently with the media rather than before it: a cover is a
            // full HTTP round trip plus two decodes, and doing it first added that
            // whole cost to the time before the video started moving. Nothing reports
            // progress in between, so the UI sat on "Обращение к API…" for it.
            val thumbnailDeferred = scope.async { media.coverUrl?.let { downloader.saveThumbnail(it) } }

            val outcome = when (media) {
                is TikWmResult.Video -> {
                    val file = downloadVideo(downloader, reporter, media)
                    reporter.report(0.97f, "Отправка в Telegram…", force = true)
                    // Last chance to bail out before the media is committed to the
                    // group. Without this a replaced download that finished
                    // downloading would happily upload its copy anyway.
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
                    reporter.report(0.97f, "Отправка карусели в Telegram…", force = true)
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

            // --- The media is in the group from here on. Nothing below may overwrite
            // this state with a failure; trailing work is best-effort only. ---
            val thumbnailPath = runCatching { thumbnailDeferred.await() }.getOrNull()
            repository.markDone(
                id = entryId,
                type = outcome.type,
                messageLink = outcome.link,
                mediaCount = outcome.mediaCount,
                thumbnailPath = thumbnailPath
            )
            sendExtraMessages(telegram, token, groupId, outcome)
            setForeground(createForegroundInfo(entryId, "Готово", 1f))

            runCatching { HapticFeedback.trigger(applicationContext, HapticFeedback.Type.SUCCESS) }
            runCatching {
                NotificationHelper.showSuccessNotification(
                    context = applicationContext,
                    title = "Успешно",
                    message = if (outcome.type == HistoryType.VIDEO) {
                        "Видео скачано и загружено в группу"
                    } else {
                        "Карусель из ${outcome.mediaCount} фото загружена в группу"
                    },
                    sound = AlertSound.parse(settings.getSuccessSound()),
                    volume = settings.getSuccessSoundVolume()
                )
            }
            runCatching { downloader.performAutoCleanup(settings.getCacheLimitMb(), settings.getCacheExpirationDays()) }

            Result.success()
        } catch (e: CancellationException) {
            // The job was stopped (user cancelled, system reclaimed it, WorkManager
            // replaced it). Do NOT write an error row - a newer request owns the row.
            Log.i(TAG, "Загрузка отменена: ${inputData.getString(KEY_URL)}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки $url", e)
            fail(repository, entryId, humanMessage(e))
            Result.failure()
        } finally {
            // Hand the files back to the cache sweeper. Without this the claims
            // taken by downloadFile would leak and protect the files forever.
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
        var file = downloader.downloadFile(media.videoUrl, "video.mp4", reporter.forItem(0, 1, "Скачивание видео…"))

        if (file.length() > MAX_TELEGRAM_BYTES && media.fallbackVideoUrl != null) {
            reporter.report(0f, "Видео слишком большое, пробуем качество ниже…", force = true)
            file.delete()
            file = downloader.downloadFile(
                media.fallbackVideoUrl,
                "video_fallback.mp4",
                reporter.forItem(0, 1, "Скачивание видео (запасное качество)…")
            )
        }

        if (file.length() > MAX_TELEGRAM_BYTES) {
            val sizeMb = file.length() / 1024 / 1024
            file.delete()
            throw IOException(
                "Видео весит $sizeMb МБ, а Telegram Bot API принимает не больше 50 МБ. " +
                    "У TikTok нет варианта меньшего размера для этого ролика."
            )
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
                reporter.forItem(index, total, "Загрузка фото (${index + 1}/$total)…")
            )
        }
    }

    /** Follow-up caption messages must never invalidate an already-successful upload. */
    private suspend fun sendExtraMessages(
        telegram: TelegramClient,
        token: String,
        groupId: String,
        outcome: Outcome
    ) {
        outcome.extraMessages.forEach { message ->
            runCatching { telegram.sendMessage(token, groupId, message, replyToMessageId = outcome.replyTo) }
                .onFailure { Log.w(TAG, "Не удалось отправить дополнительное сообщение: ${it.message}") }
        }
    }

    private suspend fun fail(repository: HistoryRepository, entryId: Long, message: String) {
        runCatching { repository.markFailed(entryId, message) }
            .onFailure { Log.e(TAG, "Не удалось записать ошибку в историю", it) }
        runCatching {
            NotificationHelper.showErrorNotification(applicationContext, "Ошибка скачивания", message)
        }
    }

    private fun humanMessage(e: Exception): String =
        e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Произошла неизвестная ошибка"

    private fun createForegroundInfo(entryId: Long, status: String, progress: Float): ForegroundInfo =
        NotificationHelper.createProgressNotification(
            context = applicationContext,
            notificationId = progressNotificationId(entryId),
            status = status,
            progress = progress,
            indeterminate = progress <= 0f
        )

    /**
     * Throttles progress writes. `setProgress` is a Room transaction on every call;
     * calling it per 8 KB read meant thousands of DB writes per download, which
     * starved the worker and contributed to it being stopped mid-transfer.
     */
    private class ProgressReporter(
        private val scope: CoroutineScope,
        private val emit: suspend (Float, String) -> Unit
    ) {
        /**
         * Written from concurrently launched coroutines, so it has to be volatile for
         * the throttle to actually hold; as a plain var several emits could slip past
         * the gate in the same tick.
         */
        @Volatile
        private var lastEmitAt = 0L

        /**
         * @param force bypasses the throttle. The phase changes ("Отправка в
         *   Telegram…", "Готово") land within milliseconds of a progress emit and
         *   used to be dropped, leaving the UI stuck on "Скачивание видео…" through
         *   the entire upload.
         */
        suspend fun report(progress: Float, status: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAt < PROGRESS_INTERVAL_MS) return
            lastEmitAt = now
            emit(progress.coerceIn(0f, 1f), status)
        }

        fun forItem(index: Int, total: Int, status: String) = object : DownloadUseCase.ProgressListener {
            override fun onProgress(percentage: Float) {
                // Gated here rather than inside `report`: the listener fires on every
                // read, and each launch costs a scheduling round trip only to be
                // dropped a moment later.
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

        /**
         * Unique work name per history row. Retrying an entry therefore *replaces*
         * any still-running attempt instead of stacking a second download of the
         * same video on top of it.
         */
        fun uniqueWorkName(entryId: Long) = "download_entry_$entryId"

        /**
         * Tag carrying the history row id. [com.example.data.StartupReconciler] reads
         * these back to tell a genuinely orphaned row from one whose worker is still
         * running.
         */
        fun entryTag(entryId: Long) = "$ENTRY_TAG_PREFIX$entryId"

        /** Exposed so the reconciler can parse the id back out of [entryTag]. */
        const val ENTRY_TAG_PREFIX_STRING = ENTRY_TAG_PREFIX

        /**
         * Distinct notification id per entry. A single shared id meant two concurrent
         * workers overwrote each other's foreground notification.
         */
        fun progressNotificationId(entryId: Long): Int =
            PROGRESS_NOTIFICATION_BASE + (entryId % PROGRESS_NOTIFICATION_SLOTS).toInt()

        private const val PROGRESS_NOTIFICATION_BASE = 1000
        private const val PROGRESS_NOTIFICATION_SLOTS = 500
    }
}