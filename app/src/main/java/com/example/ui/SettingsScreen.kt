package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.util.AlertSound
import com.example.util.CustomSounds

private const val MIN_CACHE_LIMIT_MB = 50f
private const val MAX_CACHE_LIMIT_MB = 1000f
private const val MIN_EXPIRATION_DAYS = 1f
private const val MAX_EXPIRATION_DAYS = 30f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val store = viewModel.settingsStore
    val context = androidx.compose.ui.platform.LocalContext.current
    var tokenInput by remember { mutableStateOf(store.getBotToken()) }
    var groupIdInput by remember { mutableStateOf(store.getGroupId()) }
    var showToken by remember { mutableStateOf(false) }

    // Stored values are clamped on read as well, so a corrupted preference
    // can never put the sliders into an invalid state.
    var cacheLimitMb by remember {
        mutableStateOf(store.getCacheLimitMb().toFloat().coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB))
    }
    var expirationDays by remember {
        mutableStateOf(store.getCacheExpirationDays().toFloat().coerceIn(MIN_EXPIRATION_DAYS, MAX_EXPIRATION_DAYS))
    }
    val testState by viewModel.testConnectionState.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val isTesting = testState is MainViewModel.TestState.Testing

    LaunchedEffect(Unit) {
        viewModel.updateCacheSize()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section: Telegram Bot API Settings
            Text(
                text = "Telegram API",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Telegram Bot Token") },
                modifier = Modifier.fillMaxWidth().testTag("token_input"),
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(
                        onClick = { showToken = !showToken },
                        modifier = Modifier.testTag("toggle_token_button")
                    ) {
                        Text(if (showToken) "Скрыть" else "Показать")
                    }
                }
            )

            OutlinedTextField(
                value = groupIdInput,
                onValueChange = { groupIdInput = it },
                label = { Text("Telegram Group ID") },
                placeholder = { Text("Начинается с -100") },
                modifier = Modifier.fillMaxWidth().testTag("group_id_input"),
                singleLine = true
            )

            // Connection testing row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!isTesting) viewModel.testBotConfiguration(tokenInput, groupIdInput)
                    },
                    enabled = !isTesting,
                    modifier = Modifier.testTag("test_conn_button")
                ) {
                    Text("Проверить доступ")
                }

                when (val state = testState) {
                    is MainViewModel.TestState.Testing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    is MainViewModel.TestState.Success -> Text("✅ Подключение успешно", color = MaterialTheme.colorScheme.primary)
                    is MainViewModel.TestState.Error -> Text("❌ Ошибка: ${state.errorMsg}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    else -> {}
                }
            }

            HorizontalDivider()

            // Section: Cache Management Settings
            Text(
                text = "Управление кэшем",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Display current cache size
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Размер кэша:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatBytes(cacheSize),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { viewModel.clearCache() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("clear_cache_button")
                    ) {
                        Text("Очистить кеш")
                    }
                }
            }

            // Slider: Limit Cache Size
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Предел размера кэша",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${cacheLimitMb.toInt()} МБ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = cacheLimitMb,
                    onValueChange = { cacheLimitMb = it.coerceIn(MIN_CACHE_LIMIT_MB, MAX_CACHE_LIMIT_MB) },
                    valueRange = MIN_CACHE_LIMIT_MB..MAX_CACHE_LIMIT_MB,
                    steps = 19, // 50MB intervals
                    modifier = Modifier.testTag("cache_limit_slider")
                )
                Text(
                    text = "Старые файлы будут удалены при превышении лимита.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Slider: Limit Cache Expiration Days
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Срок хранения файлов",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${expirationDays.toInt()} дн.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = expirationDays,
                    onValueChange = { expirationDays = it.coerceIn(MIN_EXPIRATION_DAYS, MAX_EXPIRATION_DAYS) },
                    valueRange = MIN_EXPIRATION_DAYS..MAX_EXPIRATION_DAYS,
                    steps = 29, // 1 day steps
                    modifier = Modifier.testTag("cache_expiration_slider")
                )
                Text(
                    text = "Файлы старше этого времени будут удаляться автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            HorizontalDivider()

            // --- Sound settings -------------------------------------------------
            // Sounds are stored as a single serialised value (see AlertSound) so a
            // bundled preset and a user-imported file are interchangeable.
            var errorSound by remember {
                mutableStateOf(AlertSound.parse(store.getErrorSound()))
            }
            var errorVolume by remember {
                mutableStateOf(store.getErrorSoundVolume().coerceIn(0f, 1f))
            }
            var successSound by remember {
                mutableStateOf(AlertSound.parse(store.getSuccessSound()))
            }
            var successVolume by remember {
                mutableStateOf(store.getSuccessSoundVolume().coerceIn(0f, 1f))
            }

            SoundPickerSection(
                title = stringResource(com.example.R.string.sound_section_error),
                selected = errorSound,
                volume = errorVolume,
                testTagPrefix = "error_sound",
                onSelect = { errorSound = it },
                onVolumeChange = { errorVolume = it.coerceIn(0f, 1f) }
            )

            HorizontalDivider()

            SoundPickerSection(
                title = stringResource(com.example.R.string.sound_section_success),
                selected = successSound,
                volume = successVolume,
                testTagPrefix = "success_sound",
                onSelect = { successSound = it },
                onVolumeChange = { successVolume = it.coerceIn(0f, 1f) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (isTesting) return@Button
                    // Clamp again on save: a limit of 0/negative would make
                    // auto-cleanup wipe the whole cache immediately.
                    store.setBotToken(tokenInput)
                    store.setGroupId(groupIdInput)
                    store.setCacheLimitMb(
                        cacheLimitMb.toInt().coerceIn(MIN_CACHE_LIMIT_MB.toInt(), MAX_CACHE_LIMIT_MB.toInt())
                    )
                    store.setCacheExpirationDays(
                        expirationDays.toInt().coerceIn(MIN_EXPIRATION_DAYS.toInt(), MAX_EXPIRATION_DAYS.toInt())
                    )
                    store.setErrorSound(AlertSound.encode(errorSound))
                    store.setErrorSoundVolume(errorVolume.coerceIn(0f, 1f))
                    store.setSuccessSound(AlertSound.encode(successSound))
                    store.setSuccessSoundVolume(successVolume.coerceIn(0f, 1f))
                    // Drop imports that are no longer referenced by either sound.
                    CustomSounds.pruneOrphans(
                        context,
                        store.storedSoundValues()
                            .map(AlertSound::parse)
                            .filterIsInstance<AlertSound.Custom>()
                            .map { it.fileName }
                            .toSet()
                    )
                    viewModel.clearTestState()
                    viewModel.autoCleanupCache()
                    onBack()
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
            ) {
                Text("Сохранить и вернуться")
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) return "$bytes Б"
    return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
