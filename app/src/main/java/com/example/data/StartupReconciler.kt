package com.example.data

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.example.worker.DownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "StartupReconciler"

/**
 * Reconciles history rows left mid-flight when the process was killed.
 *
 * This used to be a blanket "anything queued or downloading is an orphan" sweep run
 * from `MainViewModel.init`. That was wrong in two ways:
 *
 *  - It ran on *every* ViewModel construction, not once per process, so opening the
 *    app could flip a row to `error` while its worker was still uploading.
 *  - It never asked WorkManager whether the work was actually alive.
 *
 * A row flipped to `error` mid-upload shows "Повторить" in the history list, and
 * tapping it starts a second download and a second upload of the same video. That
 * was the self-perpetuating half of the duplicate-upload bug.
 *
 * Running once per process, and excluding rows that still have unfinished work,
 * means a genuinely orphaned row is cleaned up and a live one is left alone.
 */
class StartupReconciler(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run() {
        scope.launch {
            runCatching { reconcile() }.onFailure {
                // Never fatal: a failed reconciliation just leaves a stale row, which
                // the user can retry from the history list like any other.
                Log.w(TAG, "Не удалось выполнить сверку фоновых загрузок: ${it.message}")
            }
        }
    }

    private suspend fun reconcile() {
        val workManager = WorkManager.getInstance(context)
        val repository = HistoryRepository(AppDatabase.getDatabase(context).historyDao())

        // The history row is written *before* the WorkSpec is committed, so a query
        // issued too early sees a queued/downloading row with no matching work and
        // fails a download that is starting right now. Settle first, then decide once.
        delay(STARTUP_SETTLE_MS)

        repository.failOrphaned("Загрузка была прервана", workManager.liveEntryIds())
    }

    private suspend fun WorkManager.liveEntryIds(): Set<Long> {
        val infos = withContext(Dispatchers.IO) {
            getWorkInfosByTag(DownloadWorker.TAG_DOWNLOAD).get()
        }
        return infos.asSequence()
            .filterNot { it.state.isFinished }
            .mapNotNull { info ->
                info.tags.asSequence()
                    .firstOrNull { it.startsWith(DownloadWorker.ENTRY_TAG_PREFIX_STRING) }
                    ?.removePrefix(DownloadWorker.ENTRY_TAG_PREFIX_STRING)
                    ?.toLongOrNull()
            }
            .toSet()
    }

    private companion object {
        const val STARTUP_SETTLE_MS = 2_500L
    }
}