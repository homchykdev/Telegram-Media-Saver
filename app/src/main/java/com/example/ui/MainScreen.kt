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
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit
) {
    val s = strings
    var urlInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.historyEntries.collectAsState(initial = emptyList())

    // "N minutes ago" must age on its own: without a tick the label is computed once
    // per recomposition and only changes when the list data itself changes.
    var nowTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Show the outcome before clearing it.
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
        containerColor = Color(0xFFF7F8FA),
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
                    text = s.mainTitle,
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
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
                            contentDescription = s.settings,
                            tint = Color(0xFF334155),
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
                        contentDescription = s.linkIconDesc,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(24.dp)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (urlInput.isEmpty()) {
                            Text(
                                text = s.urlPlaceholder,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            )
                        }
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            textStyle = TextStyle(
                                fontSize = 15.sp,
                                color = Color(0xFF1E293B)
                            ),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tiktok_url_input")
                        )
                    }

                    // Paste Button
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
                                contentDescription = s.pasteIconDesc,
                                tint = Color(0xFF475569),
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
                    containerColor = Color(0xFFEDEFF3),
                    disabledContainerColor = Color(0xFFEDEFF3).copy(alpha = 0.5f),
                    contentColor = Color(0xFF475569),
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
                        contentDescription = s.sendIconDesc,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = s.sendButton,
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

            // 5. Section Heading: Recent Sends
            Text(
                text = s.recentSends,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
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
                            contentDescription = s.historyEmptyIconDesc,
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = s.historyEmpty,
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            } else {
                val onOpenLink: (String) -> Unit = remember(context) {
                    { link ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        } catch (e: Exception) {
                            Log.w("MainScreen", "Failed to open link: ${e.message}")
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
                            nowTick = nowTick,
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
    nowTick: Long,
    onOpenLink: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onRetry: (HistoryEntry) -> Unit
) {
    val s = strings
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
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = "Carousel",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
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
                                .offset(x = 1.dp)
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
                        text = if (entry.type == "carousel") String.format(s.typePhoto, entry.mediaCount) else s.typeVideo,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                    )
                }

                // Friendly relative timestamp
                Text(
                    text = remember(entry.timestamp, nowTick, s) { getRelativeTimeString(entry.timestamp, s) },
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                )

                Spacer(modifier = Modifier.height(2.dp))

                val isSuccess = entry.status == "done"
                val isError = entry.status == "error"

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isSuccess -> Color(0xFFF1F5F9)
                                isError -> Color(0xFFFEF2F2)
                                else -> Color(0xFFEFF6FF)
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
                                isSuccess -> Color(0xFF475569)
                                isError -> Color(0xFFEF4444)
                                else -> Color(0xFF3B82F6)
                            },
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = when {
                                isSuccess -> s.statusSent
                                isError -> s.statusError
                                else -> s.statusSending
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
                Box {
                    IconButton(
                        onClick = { expandedMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("more_button_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = s.optionsIconDesc,
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
                                text = { Text(s.menuRetry, color = Color(0xFF3B82F6)) },
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
                            text = { Text(s.menuDelete, color = Color.Red) },
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

                when (entry.status) {
                    "done" -> {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFECFDF5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    "error" -> {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFEF2F2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Error",
                                tint = Color(0xFFEF4444),
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

// Relative friendly time formatter
private fun getRelativeTimeString(timestamp: Long, s: AppStrings): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 0) return s.timeJustNow
    val minutes = diff / (60 * 1000)
    if (minutes < 1) return s.timeJustNow

    val isRu = s === AppStrings.RU

    if (minutes < 60) {
        return if (isRu) {
            "${russianPlural(minutes, "минуту", "минуты", "минут")} назад"
        } else {
            "$minutes min ago"
        }
    }

    val hours = minutes / 60
    val days = hours / 24
    val timeFormatter = TIME_FORMAT.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormatter = DATE_FORMAT.get() ?: SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    if (days >= 7) return dateFormatter.format(Date(timestamp))

    val time = timeFormatter.format(Date(timestamp))
    return when {
        isSameCalendarDay(now, timestamp) -> time
        days <= 1L -> "${s.timeYesterday} • $time"
        else -> if (isRu) {
            "${russianPlural(days, "день", "дня", "дней")} назад"
        } else {
            "$days day${if (days == 1L) "" else "s"} ago"
        }
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

private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
}

private val CAROUSEL_GRADIENT = Brush.radialGradient(
    colors = listOf(Color(0xFF818CF8), Color(0xFF6366F1))
)
private val VIDEO_GRADIENT = Brush.radialGradient(
    colors = listOf(Color(0xFFF472B6), Color(0xFFEC4899))
)
