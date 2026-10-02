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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val store = viewModel.settingsStore
    var tokenInput by remember { mutableStateOf(store.getBotToken()) }
    var groupIdInput by remember { mutableStateOf(store.getGroupId()) }
    var showToken by remember { mutableStateOf(false) }

    var cacheLimitMb by remember { mutableStateOf(store.getCacheLimitMb().toFloat()) }
    var expirationDays by remember { mutableStateOf(store.getCacheExpirationDays().toFloat()) }
    var errorSound by remember { mutableStateOf(store.getErrorSound()) }
    var errorSoundVolume by remember { mutableStateOf(store.getErrorSoundVolume()) }

    val testState by viewModel.testConnectionState.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()

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
                    onClick = { viewModel.testBotConfiguration(tokenInput, groupIdInput) },
                    enabled = testState !is MainViewModel.TestState.Testing,
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
                    onValueChange = { cacheLimitMb = it },
                    valueRange = 50f..1000f,
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
                    onValueChange = { expirationDays = it },
                    valueRange = 1f..30f,
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

            Text(
                text = "Звук уведомления об ошибке",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = errorSound == "warning",
                        onClick = { errorSound = "warning" },
                        modifier = Modifier.testTag("sound_warning_radio")
                    )
                    Text(
                        text = "Космический бластер (Предупреждение)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = errorSound == "alarm",
                        onClick = { errorSound = "alarm" },
                        modifier = Modifier.testTag("sound_alarm_radio")
                    )
                    Text(
                        text = "Сигнал тревоги (Аларм)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Громкость звука ошибки",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${(errorSoundVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = errorSoundVolume,
                    onValueChange = { errorSoundVolume = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.testTag("error_volume_slider")
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    store.setBotToken(tokenInput)
                    store.setGroupId(groupIdInput)
                    store.setCacheLimitMb(cacheLimitMb.toInt())
                    store.setCacheExpirationDays(expirationDays.toInt())
                    store.setErrorSound(errorSound)
                    store.setErrorSoundVolume(errorSoundVolume)
                    viewModel.clearTestState()
                    viewModel.autoCleanupCache()
                    onBack()
                },
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
