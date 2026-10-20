package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.HistoryEntry
import com.example.data.HistoryRepository
import com.example.data.HistoryStatus
import com.example.data.HistoryType
import com.example.data.SettingsStore
import com.example.domain.DownloadUseCase
import com.example.domain.LinkDetector
import com.example.network.HttpClients
import com.example.network.TelegramClient
import com.example.worker.DownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HistoryRepository(AppDatabase.getDatabase(application).historyDao())
    val settingsStore = SettingsStore(application)
    private val workManager = WorkManager.getInstance(application)

    private val httpClient = HttpClients.shared

    private val downloader = DownloadUseCase(application, httpClient)
    private val telegramClient = TelegramClient(httpClient)

    val historyEntries = repository.recentEntries

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _testConnectionState = MutableStateFlow<TestState>(TestState.Idle)
    val testConnectionState: StateFlow<TestState> = _testConnectionState.asStateFlow()

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    /**
     * Single collector for the active download. Previously every retry started a new
     * `collect` that was never cancelled, so a long-lived ViewModel accumulated one
     * live WorkManager observer per attempt.
     */
    private var observeJob: Job? = null
    private var activeEntryId: Long = -1L

    /**
     * True from the moment a send is accepted until the worker it created reaches a
     * state the UI can act on.
     *
     * `UiState.Processing` is only entered once a worker actually reaches RUNNING, and
     * enqueueing returns long before that, so the window between "tapped" and
     * "running" had nothing closing it. Every extra tap in that window created its own
     * history row and its own concurrent worker — each uploading its own copy of the
     * same video into the group.
     */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    init {
        // Routed through refreshCacheSize so the throttle covers it. The init block
        // used to sweep the cache on its own while leaving the timestamp at 0, so the
        // very first onResume passed the check and repeated the whole sweep — five
        // recursive walks of the cache tree, twice, on every cold start.
        refreshCacheSize(force = true)
    }

    // -------------------------------------------------------------- downloads

    fun sendMedia(rawUrl: String) {
        val url = LinkDetector.detectTikTokUrl(rawUrl)
        if (url == null) {
            _uiState.value = UiState.Error("Неверный формат ссылки. Поддерживается только TikTok.")
            return
        }
        if (!hasCredentials()) return
        if (_submitting.value) {
            Log.i(TAG, "Отправка уже выполняется")
            return
        }

        _submitting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entryId = repository.insert(
                    HistoryEntry(
                        url = url,
                        type = HistoryType.VIDEO,
                        timestamp = System.currentTimeMillis(),
                        status = HistoryStatus.QUEUED
                    )
                )
                startDownload(entryId, url)
            } catch (e: Exception) {
                _submitting.value = false
                Log.e(TAG, "Не удалось поставить загрузку в очередь", e)
                _uiState.value = UiState.Error(humanMessage(e))
            }
        }
    }

    /**
     * Re-submits an entry, but only when nothing is already running for it.
     *
     * Without this guard a double tap — or a tap on a row that is genuinely still
     * uploading — enqueued a second worker, and each one uploaded its own copy of
     * the same video into the group.
     */
    fun retryDownload(entry: HistoryEntry) {
        if (!hasCredentials()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (hasLiveWork(entry.id)) {
                Log.i(TAG, "Повтор проигнорирован: загрузка для #${entry.id} уже выполняется")
                return@launch
            }
            startDownload(entry.id, entry.url)
        }
    }

    /** True while WorkManager still has unfinished work for this history row. */
    private suspend fun hasLiveWork(entryId: Long): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.uniqueWorkName(entryId))
            .first()
            .any { !it.state.isFinished }

    /**
     * Enqueues the job as *unique* work keyed on the history row.
     *
     * `ExistingWorkPolicy.REPLACE` is the fix for the duplicate-download report:
     * any previous attempt for the same row is cancelled before the new one starts,
     * so a row can never have two workers uploading the same video at once.
     */
    private suspend fun startDownload(entryId: Long, url: String) {
        repository.markRunning(entryId)
        activeEntryId = entryId

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_ENTRY_ID to entryId
                )
            )
            .addTag(DownloadWorker.TAG_DOWNLOAD)
            .addTag(DownloadWorker.entryTag(entryId))
            .build()

        workManager.enqueueUniqueWork(DownloadWorker.uniqueWorkName(entryId), ExistingWorkPolicy.REPLACE, request)
        observe(request.id)
    }

    private fun observe(workId: java.util.UUID) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(Dispatchers.Main) {
            workManager.getWorkInfoByIdFlow(workId).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> _uiState.value = UiState.Processing(
                        progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                        statusText = info.progress.getString(DownloadWorker.KEY_STATUS) ?: "Обработка…"
                    )

                    WorkInfo.State.SUCCEEDED -> {
                        _submitting.value = false
                        _uiState.value = UiState.Success("Медиа успешно отправлено")
                        refreshCacheSize()
                    }

                    WorkInfo.State.FAILED -> {
                        _submitting.value = false
                        _uiState.value = UiState.Error(readErrorMessage())
                    }

                    WorkInfo.State.CANCELLED -> {
                        // A replacement request owns the row now; do not report an error.
                        _submitting.value = false
                        _uiState.value = UiState.Idle
                    }

                    else -> Unit
                }
            }
        }
    }

    private suspend fun readErrorMessage(): String {
        val entry = repository.get(activeEntryId) ?: return "Ошибка при скачивании или отправке"
        return entry.errorMessage ?: "Ошибка при скачивании или отправке"
    }

    fun cancelDownload(entry: HistoryEntry) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(entry.id))
        viewModelScope.launch(Dispatchers.IO) {
            // Guarded in SQL: `cancelUniqueWork` returns long before the worker has
            // actually stopped, so an unconditional write would race a worker that has
            // just committed its upload and flip a finished row to "cancelled" —
            // which offers a "Повторить" button on a video that is already in the group.
            repository.markCancelled(entry.id, "Отменено пользователем")
            refreshCacheSize()
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(entry.id))
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(entry)
            refreshCacheSize()
        }
    }

    fun isDownloading(entryId: Long): Boolean = activeEntryId == entryId && _uiState.value is UiState.Processing

    // ---------------------------------------------------------------- settings

    fun testBotConfiguration(token: String, groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _testConnectionState.value = TestState.Testing
            _testConnectionState.value = try {
                if (telegramClient.testConnection(token, groupId)) {
                    TestState.Success
                } else {
                    TestState.Error("Неверный формат токена или ID чата.")
                }
            } catch (e: Exception) {
                TestState.Error(e.localizedMessage ?: "Сетевая ошибка при проверке")
            }
        }
    }

    fun clearTestState() {
        _testConnectionState.value = TestState.Idle
    }

    private fun hasCredentials(): Boolean {
        if (settingsStore.getBotToken().isEmpty() || settingsStore.getGroupId().isEmpty()) {
            _uiState.value = UiState.Error("Пожалуйста, заполните токен бота и Group ID в настройках.")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------- cache

    /**
     * Each pass is 3-5 recursive walks of the whole cache tree, and this runs on
     * every `onResume` — so the numbers are cached briefly to keep returning to the
     * app from thrashing the disk.
     */
    fun refreshCacheSize(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCacheRefreshAt < CACHE_REFRESH_MIN_INTERVAL_MS) return
        lastCacheRefreshAt = now

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { downloader.performAutoCleanup(settingsStore.getCacheLimitMb(), settingsStore.getCacheExpirationDays()) }
            _cacheSize.value = downloader.getCacheSize()
        }
    }

    fun updateCacheSize() = refreshCacheSize(force = true)
    fun autoCleanupCache() = refreshCacheSize(force = true)

    /** Surfaces a worker failure without leaking an internal exception type to the UI. */
    private fun humanMessage(e: Exception): String =
        e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Не удалось поставить загрузку в очередь"

    fun clearCache() {
        lastCacheRefreshAt = 0L
        viewModelScope.launch(Dispatchers.IO) {
            downloader.clearTempDir(includeThumbs = true)
            _cacheSize.value = 0L
        }
    }

    fun resetUiState() {
        _uiState.value = UiState.Idle
    }

    override fun onCleared() {
        observeJob?.cancel()
        super.onCleared()
    }

    @Volatile
    private var lastCacheRefreshAt = 0L

    private companion object {
        const val TAG = "MainViewModel"
        const val CACHE_REFRESH_MIN_INTERVAL_MS = 5_000L
    }

    sealed class UiState {
        data object Idle : UiState()
        data class Processing(val progress: Float, val statusText: String) : UiState()
        data class Success(val title: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    sealed class TestState {
        data object Idle : TestState()
        data object Testing : TestState()
        data object Success : TestState()
        data class Error(val errorMsg: String) : TestState()
    }
}