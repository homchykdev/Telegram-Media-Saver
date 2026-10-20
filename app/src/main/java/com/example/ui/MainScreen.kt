package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.example.data.HistoryEntry
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.historyEntries.collectAsState(initial = emptyList())

    val snackbarHostState = remember { SnackbarHostState() }

    // Show the outcome before clearing it. Previously every error was reset to Idle
    // in the same frame it arrived and nothing ever rendered it, so a failed upload
    // produced no message on screen at all.
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is MainViewModel.UiState.Success -> {
                urlInput = "" // Clear field on success
                snackbarHostState.showSnackbar(state.title)
                viewModel.resetUiState()
            }
            is MainViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.resetUiState()
            }
            else -> {}
        }
    }

    Scaffold(
        containerColor = Color(0xFFF7F8FA), // Modern, soft off-white/light-gray background
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Custom High-Fidelity Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Media Downloader",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A), // Slate 900
                        letterSpacing = (-0.5).sp
                    )
                )

                // Settings circular card button
                Card(
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("settings_button")
                        .clickable { onOpenSettings() },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = Color(0xFF334155), // Slate 700
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 2. Custom Sleek Plain-Text Url Field inside White Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Ссылка",
                        tint = Color(0xFF94A3B8), // Slate 400
                        modifier = Modifier.size(24.dp)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (urlInput.isEmpty()) {
                            Text(
                                text = "Вставьте ссылку на TikTok",
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    color = Color(0xFF94A3B8), // Slate 400
                                )
                            )
                        }
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = Color(0xFF1E293B), // Slate 800
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tiktok_url_input")
                        )
                    }

                    // Paste Button (Small slate-light rounded box)
                    Card(
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("paste_button")
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    urlInput = clip.getItemAt(0).text?.toString() ?: ""
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Вставить",
                                tint = Color(0xFF475569), // Slate 600
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 3. Elegant soft-bluish Send Button
            Button(
                onClick = { viewModel.sendMedia(urlInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("send_button"),
                enabled = uiState !is MainViewModel.UiState.Processing && urlInput.isNotEmpty(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEDEFF3), // Soft light gray-bluish
                    disabledContainerColor = Color(0xFFEDEFF3).copy(alpha = 0.5f),
                    contentColor = Color(0xFF475569), // Dark gray-bluish text
                    disabledContentColor = Color(0xFF475569).copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Отправить в Telegram",
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            // 4. Dynamic Progress status tracking bar
            AnimatedVisibility(visible = uiState is MainViewModel.UiState.Processing) {
                val state = uiState as? MainViewModel.UiState.Processing
                state?.let {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = it.statusText,
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Medium
                            )
                        )
                        LinearProgressIndicator(
                            progress = { it.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .testTag("progress_indicator"),
                            color = Color(0xFF3B82F6),
                            trackColor = Color(0xFFE2E8F0)
                        )
                    }
                }
            }

            // 5. Section Heading: "Последние отправки"
            Text(
                text = "Последние отправки",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B) // Slate 800
                ),
                modifier = Modifier.padding(top = 8.dp)
            )

            // 6. History List View
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Пусто",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "История пуста. Отправьте ссылку первыми!",
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            } else {
                // Hoisted out of `items`. The item-content lambda captured the
                // ViewModel, which Compose infers unstable, so the compiler never
                // memoised it and the LazyColumn rebuilt its whole item provider — and
                // re-boxed a key for every row — four times a second while a download
                // reported progress.
                val onOpenLink: (String) -> Unit = remember(context) {
                    { link ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (e: Exception) {
                            Log.w("MainScreen", "Не удалось открыть ссылку: ${e.message}")
                        }
                    }
                }
                val onDelete = remember(viewModel) {
                    { entry: HistoryEntry -> viewModel.deleteHistoryEntry(entry) }
                }
                val onRetry = remember(viewModel) {
                    { entry: HistoryEntry -> viewModel.retryDownload(entry) }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(history, key = { it.id }) { entry ->
                        HistoryCard(
                            entry = entry,
                            onOpenLink = onOpenLink,
                            onDelete = onDelete,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryCard(
    entry: HistoryEntry,
    onOpenLink: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onRetry: (HistoryEntry) -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_card_${entry.id}")
            .combinedClickable(
                onClick = { entry.messageLink?.let(onOpenLink) },
                onLongClick = { expandedMenu = true }
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Beautiful Gradient Thumbnail Placeholder on the Left
            //
            // Keyed on the path so the existence check — a blocking stat() — happens
            // once per row rather than on every recomposition of every visible row.
            val thumbFile = remember(entry.thumbnailPath) {
                entry.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }
            }

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (thumbFile != null) {
                            Modifier
                        } else {
                            Modifier.background(
                                if (entry.type == "carousel") CAROUSEL_GRADIENT else VIDEO_GRADIENT
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = "Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Play overlay or photo count overlay
                if (entry.type == "carousel") {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${entry.mediaCount} 🖼️",
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    if (thumbFile == null) {
                        // Photo stack icon in the center if no thumbnail
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = "Carousel",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    // Play icon in center
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color(0xFFEC4899),
                            modifier = Modifier
                                .size(16.dp)
                                .offset(x = 1.dp) // Align arrow visually
                        )
                    }
                }
            }

            // 2. Middle Block (Type tag, date, sending badge)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Small Category badge icon
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (entry.type == "carousel") Color(0xFFEEF2F6) else Color(0xFFFDF2F8)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (entry.type == "carousel") Icons.Default.Image else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = if (entry.type == "carousel") Color(0xFF6366F1) else Color(0xFFEC4899),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Text(
                        text = if (entry.type == "carousel") "Фото (${entry.mediaCount})" else "Видео",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                    )
                }

                // Friendly relative timestamp
                Text(
                    text = remember(entry.timestamp) { getRelativeTimeString(entry.timestamp) },
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Custom Badge: "Отправлено в Telegram" or "Ошибка отправки"
                val isSuccess = entry.status == "done"
                val isError = entry.status == "error"
                val isDownloading = entry.status == "downloading"

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isSuccess -> Color(0xFFF1F5F9) // Slate 100
                                isError -> Color(0xFFFEF2F2) // Red 50
                                else -> Color(0xFFEFF6FF) // Blue 50
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isSuccess -> Icons.AutoMirrored.Filled.Send
                                isError -> Icons.Default.ErrorOutline
                                else -> Icons.Default.CloudDownload
                            },
                            contentDescription = null,
                            tint = when {
                                isSuccess -> Color(0xFF475569) // Slate 600
                                isError -> Color(0xFFEF4444) // Red 500
                                else -> Color(0xFF3B82F6) // Blue 500
                            },
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = when {
                                isSuccess -> "Отправлено в Telegram"
                                isError -> "Ошибка отправки"
                                else -> "Отправка…"
                            },
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isSuccess -> Color(0xFF475569)
                                    isError -> Color(0xFFEF4444)
                                    else -> Color(0xFF3B82F6)
                                }
                            )
                        )
                    }
                }
            }

            // 3. Right Status circles + Context Menu
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight()
            ) {
                // More options Menu button
                Box {
                    IconButton(
                        onClick = { expandedMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("more_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Опции",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { expandedMenu = false }
                    ) {
                        if (entry.status == "error") {
                            DropdownMenuItem(
                                text = { Text("Повторить", color = Color(0xFF3B82F6)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color(0xFF3B82F6)
                                    )
                                },
                                onClick = {
                                    expandedMenu = false
                                    onRetry(entry)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Удалить", color = Color.Red) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color.Red
                                )
                            },
                            onClick = {
                                expandedMenu = false
                                onDelete(entry)
                            },
                            modifier = Modifier.testTag("delete_item_button_${entry.id}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Beautiful circle status indicator (Success: green check, Error: red cross, Progress: spinner)
                when (entry.status) {
                    "done" -> {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFECFDF5)), // Green 50
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = Color(0xFF10B981), // Green 500
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    "error" -> {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFEF2F2)), // Red 50
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Error",
                                tint = Color(0xFFEF4444), // Red 500
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    else -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF3B82F6),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

// Relative friendly time formatter matching the screenshot values perfectly
private fun getRelativeTimeString(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 0) return "Только что"
    val minutes = diff / (60 * 1000)
    if (minutes < 1) return "Только что"
    if (minutes < 60) return "${russianPlural(minutes, "минуту", "минуты", "минут")} назад"

    val hours = minutes / 60
    val days = hours / 24
    if (days >= 7) return DATE_FORMAT.get().format(Date(timestamp))

    val time = TIME_FORMAT.get().format(Date(timestamp))
    return when {
        // YEAR has to be compared too: DAY_OF_YEAR alone makes 1 Jan 2024 look like
        // "today" when the same date is viewed on 1 Jan of a later year.
        isSameCalendarDay(now, timestamp) -> time
        // 24-47 hours ago is the previous calendar day in all but a DST edge case.
        days <= 1L -> "Вчера • $time"
        else -> "${russianPlural(days, "день", "дня", "дней")} назад"
    }
}

private fun isSameCalendarDay(a: Long, b: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = a }
    val second = Calendar.getInstance().apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

/** Russian requires three plural forms, keyed on the last one or two digits. */
private fun russianPlural(count: Long, one: String, few: String, many: String): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> "$count $many"
        last == 1L -> "$count $one"
        last in 2..4 -> "$count $few"
        else -> "$count $many"
    }
}

/**
 * `SimpleDateFormat` builds a `DecimalFormat` and a `Calendar` per construction, and
 * it is not thread-safe — hence one instance per thread rather than a shared one.
 */
private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
}

/**
 * Built once rather than per row per composition. Each `Brush.radialGradient` call
 * allocates a colour list, a brush and — at draw time — a new shader object.
 */
private val CAROUSEL_GRADIENT = Brush.radialGradient(
    colors = listOf(Color(0xFF818CF8), Color(0xFF6366F1)) // Beautiful Purple-Blue
)
private val VIDEO_GRADIENT = Brush.radialGradient(
    colors = listOf(Color(0xFFF472B6), Color(0xFFEC4899)) // Beautiful Pink-Red
)
