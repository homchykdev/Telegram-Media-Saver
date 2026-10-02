package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.HistoryEntry
import com.example.data.HistoryRepository
import com.example.data.SettingsStore
import com.example.domain.DownloadUseCase
import com.example.domain.LinkDetector
import com.example.network.TelegramClient
import com.example.network.TikWmClient
import com.example.network.TikWmResult
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val historyDao = db.historyDao()
    private val repository = HistoryRepository(historyDao)
    val historyEntries = repository.allEntries

    val settingsStore = SettingsStore(application)
    private val client = OkHttpClient()
    private val tikWmClient = TikWmClient(client)
    private val downloadUseCase = DownloadUseCase(application, client)
    private val telegramClient = TelegramClient(client)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _sharedUrlFlow = MutableStateFlow("")
    val sharedUrlFlow: StateFlow<String> = _sharedUrlFlow.asStateFlow()

    fun setSharedUrl(url: String) {
        _sharedUrlFlow.value = url
    }
    
    fun clearSharedUrl() {
        _sharedUrlFlow.value = ""
    }

    private val _testConnectionState = MutableStateFlow<TestState>(TestState.Idle)
    val testConnectionState: StateFlow<TestState> = _testConnectionState.asStateFlow()

    private val _cacheSize = MutableStateFlow<Long>(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    init {
        autoCleanupCache()
    }

    fun updateCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSize.value = downloadUseCase.getCacheSize()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            downloadUseCase.clearTempDir(includeThumbs = true)
            _cacheSize.value = 0L
        }
    }

    fun autoCleanupCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val limitMb = settingsStore.getCacheLimitMb()
            val days = settingsStore.getCacheExpirationDays()
            downloadUseCase.performAutoCleanup(limitMb, days)
            updateCacheSize()
        }
    }

    sealed class UiState {
        object Idle : UiState()
        data class Processing(val progress: Float, val statusText: String) : UiState()
        data class Success(val title: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    sealed class TestState {
        object Idle : TestState()
        object Testing : TestState()
        object Success : TestState()
        data class Error(val errorMsg: String) : TestState()
    }

    fun sendMedia(url: String) {
        val detectedUrl = LinkDetector.detectTikTokUrl(url)
        if (detectedUrl == null) {
            _uiState.value = UiState.Error("Неверный формат ссылки. Поддерживается только TikTok.")
            return
        }

        val token = settingsStore.getBotToken()
        val groupId = settingsStore.getGroupId()

        if (token.isEmpty() || groupId.isEmpty()) {
            _uiState.value = UiState.Error("Пожалуйста, заполните токен бота и Group ID в настройках.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val dbId = repository.insert(
                HistoryEntry(
                    url = detectedUrl,
                    type = "video",
                    timestamp = System.currentTimeMillis(),
                    status = "downloading"
                )
            )

            val workRequest = OneTimeWorkRequestBuilder<com.example.worker.DownloadWorker>()
                .setInputData(
                    workDataOf(
                        "url" to detectedUrl,
                        "entryId" to dbId
                    )
                )
                .addTag("download_media")
                .build()

            WorkManager.getInstance(getApplication()).enqueue(workRequest)
            observeWorkStatus(workRequest.id)
        }
    }
    
    fun retryDownload(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(entry.copy(status = "downloading", timestamp = System.currentTimeMillis(), errorMessage = null))
            val workRequest = OneTimeWorkRequestBuilder<com.example.worker.DownloadWorker>()
                .setInputData(
                    workDataOf(
                        "url" to entry.url,
                        "entryId" to entry.id
                    )
                )
                .addTag("download_media")
                .build()

            WorkManager.getInstance(getApplication()).enqueue(workRequest)
            observeWorkStatus(workRequest.id)
        }
    }

    private fun observeWorkStatus(workId: java.util.UUID) {
        viewModelScope.launch(Dispatchers.Main) {
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(workId)
                .collect { workInfo ->
                    if (workInfo != null) {
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                val progress = workInfo.progress.getFloat("progress", 0f)
                                val status = workInfo.progress.getString("status") ?: "Обработка..."
                                _uiState.value = UiState.Processing(progress, status)
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                _uiState.value = UiState.Success("Медиа успешно отправлено")
                            }
                            WorkInfo.State.FAILED -> {
                                _uiState.value = UiState.Error("Ошибка при скачивании или отправке")
                            }
                            else -> {}
                        }
                    }
                }
        }
    }

    fun testBotConfiguration(token: String, groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _testConnectionState.value = TestState.Testing
            try {
                val ok = telegramClient.testConnection(token, groupId)
                if (ok) {
                    _testConnectionState.value = TestState.Success
                } else {
                    _testConnectionState.value = TestState.Error("Доступ к чату отсутствует. Проверьте ID.")
                }
            } catch (e: Exception) {
                _testConnectionState.value = TestState.Error(e.localizedMessage ?: "Сетевая ошибка при проверке")
            }
        }
    }

    fun clearTestState() {
        _testConnectionState.value = TestState.Idle
    }

    fun deleteHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(entry)
        }
    }

    fun resetUiState() {
        _uiState.value = UiState.Idle
    }
}
