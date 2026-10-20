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
import com.example.worker.DownloadWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share-sheet entry point.
 *
 * This activity uses a fully transparent window so the source app (TikTok, browser, etc.)
 * remains visible behind it. The bottom sheet card is drawn as a Compose composable that
 * sits at the bottom of the screen — identical to how YTDLnis renders its share overlay.
 *
 * Key properties that make this work:
 *   • Theme.MediaSaver.Translucent  → transparent window background before Compose starts
 *   • windowIsFloating=false        → keeps the activity full-screen so insets work correctly
 *   • taskAffinity=<appId>.share    → own task: dismiss returns to the source app
 *   • excludeFromRecents=true       → never appears in the Recents list
 *   • singleTask + onNewIntent      → a second share replaces the link in the open sheet
 *   • The scrim (clicking outside the card) calls finish() to dismiss
 */
class ShareActivity : ComponentActivity() {

    /**
     * The link currently shown in the sheet.
     *
     * Held as state rather than a local so a second share can replace it: this
     * activity is `singleInstance`, so the system delivers the next share to
     * [onNewIntent] instead of creating a new instance, and a sheet still showing the
     * previous link would submit that link again — a second upload of a video the user
     * never asked for twice.
     */
    private val sharedUrl = mutableStateOf<String?>(null)

    /** Set from the moment a submit is accepted until the sheet is torn down. */
    private val submitting = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Make both status bar and navigation bar fully transparent so the source
        // app's content shines through edge-to-edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)

        // Keep the window itself transparent. The card Surface handles its own background.
        window.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // Allow the activity to draw behind system bars.
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

    /** Reads the shared payload from either an ACTION_SEND or an ACTION_VIEW intent. */
    private fun Intent.extractSharedText(): String? = when (action) {
        Intent.ACTION_SEND -> when (type) {
            "text/plain" -> getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        Intent.ACTION_VIEW -> dataString
        else -> null
    }

    /**
     * Validates, records the entry and enqueues the worker. Returns false when the
     * user still needs to fix something (bad link or missing credentials), in which
     * case the sheet stays open.
     */
    private fun submit(url: String): Boolean {
        // Each submit inserts its own history row, and the unique work name is derived
        // from that row's id — so two accepted taps mean two unrelated workers, both of
        // which upload the same video. The sheet stays open until finish(), and a fast
        // double tap easily lands inside that window.
        if (submitting.value) return false

        // Re-detected rather than trusted: the value that reaches the caption is
        // interpolated into HTML with parse_mode=HTML, so a stray `<` from an
        // arbitrary shared text would make Telegram reject the whole upload.
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
                    // Without this the startup reconciler cannot tell a live
                    // share-sheet download from an orphaned row.
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

/**
 * Full-screen overlay with a transparent scrim and a bottom-anchored card.
 *
 * Tapping the scrim area (outside the card) calls [onDismiss] so the user can
 * cancel without pressing the close button — same UX as a BottomSheetDialog.
 */
@Composable
private fun ShareOverlay(
    url: String?,
    submitting: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on `url` so a second share (delivered to the same singleInstance activity)
    // replaces the link instead of keeping the previous one on screen.
    var urlInput by remember(url) { mutableStateOf(url ?: "") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Semi-transparent dark scrim — tapping it dismisses the sheet.
            .background(Color(0x80000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The card itself. Clicks on it must NOT propagate to the scrim.
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // consume the click so it doesn't reach the scrim
                )
                // Pad the bottom edge away from the navigation bar.
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Handle bar (decorative)
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

/** Retrieves a string resource from the current composable context. */
@Composable
private fun getString(id: Int): String =
    androidx.compose.ui.platform.LocalContext.current.getString(id)