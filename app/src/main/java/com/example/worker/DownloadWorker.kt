package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.HistoryEntry
import com.example.data.HistoryRepository
import com.example.data.SettingsStore
import com.example.domain.DownloadUseCase
import com.example.network.TelegramClient
import com.example.network.TikWmClient
import com.example.network.TikWmResult
import com.example.util.HapticFeedback
import com.example.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val coroutineScope = this
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val entryId = inputData.getLong("entryId", -1L)
        if (entryId == -1L) return@withContext Result.failure()

        val db = AppDatabase.getDatabase(applicationContext)
        val repository = HistoryRepository(db.historyDao())
        val settingsStore = SettingsStore(applicationContext)

        val client = OkHttpClient()
        val tikWmClient = TikWmClient(client)
        val downloadUseCase = DownloadUseCase(applicationContext, client)
        val telegramClient = TelegramClient(client)

        val token = settingsStore.getBotToken()
        val groupId = settingsStore.getGroupId()

        if (token.isEmpty() || groupId.isEmpty()) {
            val errorMsg = "Токен бота или Group ID не заполнены в настройках"
            updateDbError(repository, entryId, url, errorMsg, null)
            NotificationHelper.showErrorNotification(
                applicationContext,
                "Ошибка загрузки",
                errorMsg
            )
            return@withContext Result.failure()
        }

        var thumbnailPath: String? = null
        try {
            // Trigger start vibration
            HapticFeedback.trigger(applicationContext, HapticFeedback.Type.START)

            setProgress(workDataOf("progress" to 0f, "status" to "Обращение к API TikWM…"))

            val mediaInfo = tikWmClient.fetchMedia(url)
            val titleStr = mediaInfo.title ?: ""
            val fullText = "📎 <b>$titleStr</b>\n🔗 $url"
            
            val caption: String?
            val extraMessages: List<String>
            if (fullText.length <= 1024) {
                caption = fullText
                extraMessages = emptyList()
            } else {
                caption = "🔗 $url"
                // Split the full text into 4096 chunks (Telegram limit for text message)
                extraMessages = fullText.chunked(4096)
            }

            // Fetch and save thumbnail to cache!
            mediaInfo.coverUrl?.let { coverUrl ->
                try {
                    thumbnailPath = downloadUseCase.saveThumbnail(coverUrl)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }

            val messageLink = when (mediaInfo) {
                is TikWmResult.Video -> {
                    var file = downloadUseCase.downloadFile(mediaInfo.videoUrl, "video.mp4", object : DownloadUseCase.ProgressListener {
                        override fun onProgress(percentage: Float) {
                            coroutineScope.launch {
                                setProgress(workDataOf("progress" to percentage, "status" to "Скачивание видео…"))
                            }
                        }
                    })
                    
                    if (file.length() > 50 * 1024 * 1024 && mediaInfo.fallbackVideoUrl != null) {
                        coroutineScope.launch {
                            setProgress(workDataOf("progress" to 0f, "status" to "Видео слишком большое, пробуем другое качество…"))
                        }
                        file.delete()
                        file = downloadUseCase.downloadFile(mediaInfo.fallbackVideoUrl, "video_fallback.mp4", object : DownloadUseCase.ProgressListener {
                            override fun onProgress(percentage: Float) {
                                coroutineScope.launch {
                                    setProgress(workDataOf("progress" to percentage, "status" to "Скачивание видео (fallback)…"))
                                }
                            }
                        })
                    }

                    if (file.length() > 50 * 1024 * 1024) {
                        throw Exception("Файл слишком большой (> 50МБ). Telegram Bot API не поддерживает загрузку таких файлов.")
                    }

                    setProgress(workDataOf("progress" to 1f, "status" to "Отправка в Telegram…"))
                    val link = telegramClient.sendVideo(token, groupId, file, caption)
                    val baseMessageId = link.substringAfterLast("/").toLongOrNull()
                    
                    for (msg in extraMessages) {
                        telegramClient.sendMessage(token, groupId, msg, replyToMessageId = baseMessageId)
                    }
                    link
                }
                is TikWmResult.Carousel -> {
                    val files = mutableListOf<File>()
                    mediaInfo.imageUrls.forEachIndexed { i, imgUrl ->
                        val file = downloadUseCase.downloadFile(imgUrl, "img_$i.jpg", object : DownloadUseCase.ProgressListener {
                            override fun onProgress(percentage: Float) {
                                val totalProgress = (i + percentage) / mediaInfo.imageUrls.size
                                coroutineScope.launch {
                                    setProgress(workDataOf("progress" to totalProgress, "status" to "Загрузка фото (${i + 1}/${mediaInfo.imageUrls.size})…"))
                                }
                            }
                        })
                        files.add(file)
                    }
                    
                    setProgress(workDataOf("progress" to 1f, "status" to "Отправка карусели в Telegram…"))
                    
                    // Split files into chunks of 10
                    val fileChunks = files.chunked(10)
                    var firstMessageId: Long? = null
                    
                    fileChunks.forEachIndexed { index, chunk ->
                        val currentCaption = if (index == 0) caption else null
                        val replyTo = if (index > 0) firstMessageId else null
                        val msgId = telegramClient.sendMediaGroup(token, groupId, chunk, currentCaption, replyToMessageId = replyTo)
                        if (index == 0) {
                            firstMessageId = msgId
                        }
                    }
                    
                    for (msg in extraMessages) {
                        telegramClient.sendMessage(token, groupId, msg, replyToMessageId = firstMessageId)
                    }
                    
                    telegramClient.formatMessageLink(groupId, firstMessageId!!)
                }
            }

            val mediaCount = when (mediaInfo) {
                is TikWmResult.Video -> 0
                is TikWmResult.Carousel -> mediaInfo.imageUrls.size
            }

            val updatedEntry = HistoryEntry(
                id = entryId,
                url = url,
                type = if (mediaInfo is TikWmResult.Video) "video" else "carousel",
                timestamp = System.currentTimeMillis(),
                status = "done",
                messageLink = messageLink,
                mediaCount = mediaCount,
                thumbnailPath = thumbnailPath
            )
            repository.update(updatedEntry)

            // Success feedback (slightly stronger vibration + silent notification)
            HapticFeedback.trigger(applicationContext, HapticFeedback.Type.SUCCESS)
            val mediaTypeText = if (mediaInfo is TikWmResult.Video) "Видео" else "Карусель фото"
            NotificationHelper.showSuccessNotification(
                applicationContext,
                "Успешно",
                "$mediaTypeText скачано и загружено в группу"
            )

            // Cache auto-cleanup
            val limitMb = settingsStore.getCacheLimitMb()
            val days = settingsStore.getCacheExpirationDays()
            downloadUseCase.performAutoCleanup(limitMb, days)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            updateDbError(repository, entryId, url, e.localizedMessage ?: "Неизвестная ошибка", thumbnailPath)
            
            // Error feedback: notification with selected sound and high importance
            NotificationHelper.showErrorNotification(
                applicationContext,
                "Ошибка скачивания",
                e.localizedMessage ?: "Произошла неизвестная ошибка"
            )

            // Cache auto-cleanup on failure
            try {
                val limitMb = settingsStore.getCacheLimitMb()
                val days = settingsStore.getCacheExpirationDays()
                downloadUseCase.performAutoCleanup(limitMb, days)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }

            Result.failure()
        }
    }

    private suspend fun updateDbError(
        repository: HistoryRepository,
        entryId: Long,
        url: String,
        errorMsg: String,
        thumbnailPath: String?
    ) {
        try {
            repository.update(
                HistoryEntry(
                    id = entryId,
                    url = url,
                    type = "video",
                    timestamp = System.currentTimeMillis(),
                    status = "error",
                    errorMessage = errorMsg,
                    thumbnailPath = thumbnailPath
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
