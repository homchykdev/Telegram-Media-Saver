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
import com.example.R
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
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

    /** Entries whose download is enqueued or running, whatever the focused bar shows. */
    private val activeEntryIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    )
    /** Work id the main progress bar is focused on: the one started last. */
    @Volatile
    private var focusedWorkId: java.util.UUID? = null

    /** One WorkManager observer per download; entries clean up on terminal states. */
    private val observers = mutableMapOf<java.util.UUID, Job>()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    init {
        refreshCacheSize(force = true)
    }

    // -------------------------------------------------------------- downloads

    fun sendMedia(rawUrl: String) {
        val url = LinkDetector.detectTikTokUrl(rawUrl)
        if (url == null) {
            _uiState.value = UiState.Error(getApplication<Application>().getString(R.string.error_invalid_url))
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

    private suspend fun hasLiveWork(entryId: Long): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(DownloadWorker.uniqueWorkName(entryId))
            .first()
            .any { !it.state.isFinished }

    private suspend fun startDownload(entryId: Long, url: String) {
        repository.markRunning(entryId)
        activeEntryIds.add(entryId)

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
        observe(request.id, entryId)
    }

    private fun observe(workId: java.util.UUID, entryId: Long) {
        observers.remove(workId)?.cancel()
        focusedWorkId = workId
        val job = viewModelScope.launch(Dispatchers.Main) {
            workManager.getWorkInfoByIdFlow(workId)
                .onEach { info ->
                    when {
                        info == null -> Unit
                        info.state == WorkInfo.State.RUNNING -> {
                            // Only the focused download writes the shared bar; a
                            // second, still-running job must not pull the percentage
                            // backwards while the user watches another one.
                            if (focusedWorkId == workId) {
                                _uiState.value = UiState.Processing(
                                    progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                                    statusText = info.progress.getString(DownloadWorker.KEY_STATUS)
                                        ?: getApplication<Application>().getString(R.string.status_processing)
                                )
                            }
                        }

                        info.state == WorkInfo.State.SUCCEEDED -> {
                            if (focusedWorkId == workId) {
                                _submitting.value = false
                                _uiState.value = UiState.Success(getApplication<Application>().getString(R.string.msg_media_sent))
                                refreshCacheSize()
                            }
                        }

                        info.state == WorkInfo.State.FAILED -> {
                            if (focusedWorkId == workId) {
                                _submitting.value = false
                                _uiState.value = UiState.Error(readErrorMessage(entryId))
                            }
                        }

                        info.state == WorkInfo.State.CANCELLED -> {
                            if (focusedWorkId == workId) {
                                _submitting.value = false
                                _uiState.value = UiState.Idle
                            }
                        }

                        else -> Unit
                    }
                }
                // Completes right after the first terminal state was handled, so a
                // finished download does not keep a collector subscribed forever.
                // Null emissions (row not yet readable) never finish the flow.
                .takeWhile { it?.state?.isFinished != true }
                .collect()
        }
        job.invokeOnCompletion {
            observers.remove(workId)
            activeEntryIds.remove(entryId)
        }
        observers[workId] = job
    }

    private suspend fun readErrorMessage(entryId: Long): String {
        val entry = repository.get(entryId) ?: return getApplication<Application>().getString(R.string.error_download_or_send)
        return entry.errorMessage ?: getApplication<Application>().getString(R.string.error_download_or_send)
    }

    fun cancelDownload(entry: HistoryEntry) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(entry.id))
        viewModelScope.launch(Dispatchers.IO) {
            repository.markCancelled(
                entry.id,
                getApplication<Application>().getString(R.string.status_cancelled_by_user)
            )
            refreshCacheSize()
        }
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        workManager.cancelUniqueWork(DownloadWorker.uniqueWorkName(entry.id))
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(entry)
            // The row is gone; its preview otherwise stays in the cache until the
            // expiration timer runs out (up to 30 days of dead bytes).
            entry.thumbnailPath?.let { downloader.deleteThumbnail(it) }
            refreshCacheSize()
        }
    }

    /**
     * True while a download for [entryId] is enqueued or running, even if the main
     * progress bar is focused on a different entry.
     */
    fun isDownloading(entryId: Long): Boolean = activeEntryIds.contains(entryId)

    // ---------------------------------------------------------------- settings

    fun testBotConfiguration(token: String, groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _testConnectionState.value = TestState.Testing
            _testConnectionState.value = try {
                if (telegramClient.testConnection(token, groupId)) {
                    TestState.Success
                } else {
                    TestState.Error(getApplication<Application>().getString(R.string.error_invalid_token_or_group))
                }
            } catch (e: Exception) {
                TestState.Error(e.localizedMessage ?: getApplication<Application>().getString(R.string.error_network_test))
            }
        }
    }

    fun clearTestState() {
        _testConnectionState.value = TestState.Idle
    }

    private fun hasCredentials(): Boolean {
        if (settingsStore.getBotToken().isEmpty() || settingsStore.getGroupId().isEmpty()) {
            _uiState.value = UiState.Error(getApplication<Application>().getString(R.string.error_missing_credentials))
            return false
        }
        return true
    }

    // ------------------------------------------------------------------- cache

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

    private fun humanMessage(e: Exception): String =
        e.localizedMessage?.takeIf { it.isNotBlank() } ?: getApplication<Application>().getString(R.string.error_enqueue_failed)

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
