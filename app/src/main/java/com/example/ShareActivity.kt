package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.HistoryEntry
import com.example.data.HistoryRepository
import com.example.data.HistoryStatus
import com.example.data.HistoryType
import com.example.data.SettingsStore
import com.example.domain.LinkDetector
import com.example.ui.theme.MyApplicationTheme
import com.example.util.LocaleHelper
import com.example.worker.DownloadWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet entry point.
 */
class ShareActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf<String?>(null)
    private val submitting = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsStore(this)
        LocaleHelper.applyLocale(this, settings.getLanguage())

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        sharedUrl.value = intent.detectUrl()

        setContent {
            MyApplicationTheme {
                ShareOverlay(
                    url = sharedUrl.value,
                    submitting = submitting.value,
                    onConfirm = { url -> submit(url) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl.value = intent.detectUrl()
    }

    private fun Intent.detectUrl(): String? = LinkDetector.detectTikTokUrl(extractSharedText())

    private fun Intent.extractSharedText(): String? = when (action) {
        Intent.ACTION_SEND -> when (type) {
            "text/plain" -> getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        Intent.ACTION_VIEW -> dataString
        else -> null
    }

    private fun submit(url: String): Boolean {
        if (submitting.value) return false

        val detected = LinkDetector.detectTikTokUrl(url)
        if (detected == null) {
            toast(getString(R.string.share_sheet_unsupported))
            return false
        }

        val settings = SettingsStore(this)
        if (settings.getBotToken().isEmpty() || settings.getGroupId().isEmpty()) {
            toast(getString(R.string.error_missing_credentials))
            return false
        }

        submitting.value = true
        val repository = HistoryRepository(AppDatabase.getDatabase(this).historyDao())
        val appContext = applicationContext
        lifecycleScope.launch {
            withContext(NonCancellable) {
                val entryId = repository.insert(
                    HistoryEntry(
                        url = detected,
                        type = HistoryType.VIDEO,
                        timestamp = System.currentTimeMillis(),
                        status = HistoryStatus.QUEUED
                    )
                )

                val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(
                        workDataOf(
                            DownloadWorker.KEY_URL to detected,
                            DownloadWorker.KEY_ENTRY_ID to entryId
                        )
                    )
                    .addTag(DownloadWorker.TAG_DOWNLOAD)
                    .addTag(DownloadWorker.entryTag(entryId))
                    .build()

                WorkManager.getInstance(appContext)
                    .enqueueUniqueWork(
                        DownloadWorker.uniqueWorkName(entryId),
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
            }
            toast(getString(R.string.share_sheet_queued))
            finish()
        }
        return true
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun ShareOverlay(
    url: String?,
    submitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var urlInput by remember(url) { mutableStateOf(url ?: "") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getString(R.string.share_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (url != null) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = getString(R.string.share_sheet_unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = { onConfirm(urlInput) },
                    enabled = urlInput.isNotEmpty() && !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = getString(R.string.share_sheet_confirm),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun getString(id: Int): String =
    androidx.compose.ui.platform.LocalContext.current.getString(id)
