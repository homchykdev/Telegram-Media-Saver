package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.AlertSound
import com.example.util.CustomSounds
import com.example.util.LocaleHelper

private const val MIN_CACHE_LIMIT_MB = 50f
private const val MAX_CACHE_LIMIT_MB = 1000f
private const val MIN_EXPIRATION_DAYS = 1f
private const val MAX_EXPIRATION_DAYS = 30f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: (newLangCode: String) -> Unit
) {
    val store = viewModel.settingsStore
    val context = LocalContext.current
    var tokenInput by remember { mutableStateOf(store.getBotToken()) }
    var groupIdInput by remember { mutableStateOf(store.getGroupId()) }
    var showToken by remember { mutableStateOf(false) }

    var selectedLang by remember {
        mutableStateOf(if (store.getLanguage() == "ru") Lang.RU else Lang.EN)
    }

    // Dynamic strings reflecting the selected language in Settings
    val s = if (selectedLang == Lang.EN) AppStrings.EN else AppStrings.RU

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

    // Update system locale if user toggles language so resource lookups match
    LaunchedEffect(selectedLang) {
        val code = if (selectedLang == Lang.EN) "en" else "ru"
        LocaleHelper.applyLocale(context, code)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.settingsTitle, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val code = if (selectedLang == Lang.EN) "en" else "ru"
                            store.setLanguage(code)
                            onBack(code)
                        },
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
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
            // ── Section: Language ──────────────────────────────────────────────
            Text(
                text = s.sectionLanguage,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsLanguageCard(
                    flag = "🇬🇧",
                    label = "English",
                    selected = selectedLang == Lang.EN,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Apply the locale synchronously so resource-based strings
                        // (sound pickers) resolve in the new language during the very
                        // recomposition triggered by the state update below.
                        LocaleHelper.applyLocale(context, "en")
                        selectedLang = Lang.EN
                        store.setLanguage("en")
                    }
                )
                SettingsLanguageCard(
                    flag = "🇷🇺",
                    label = "Русский",
                    selected = selectedLang == Lang.RU,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        LocaleHelper.applyLocale(context, "ru")
                        selectedLang = Lang.RU
                        store.setLanguage("ru")
                    }
                )
            }

            HorizontalDivider()

            // ── Section: Telegram Bot API Settings ─────────────────────────────
            Text(
                text = s.sectionTelegramApi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text(s.labelBotToken) },
                modifier = Modifier.fillMaxWidth().testTag("token_input"),
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(
                        onClick = { showToken = !showToken },
                        modifier = Modifier.testTag("toggle_token_button")
                    ) {
                        Text(if (showToken) s.hideToken else s.showToken)
                    }
                }
            )

            OutlinedTextField(
                value = groupIdInput,
                onValueChange = { groupIdInput = it },
                label = { Text(s.labelGroupId) },
                placeholder = { Text(s.groupIdPlaceholder) },
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
                    Text(s.testConnection)
                }

                when (val state = testState) {
                    is MainViewModel.TestState.Testing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    is MainViewModel.TestState.Success -> Text(s.testSuccess, color = MaterialTheme.colorScheme.primary)
                    is MainViewModel.TestState.Error -> Text(
                        s.testError + state.errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                    else -> {}
                }
            }

            HorizontalDivider()

            // ── Section: Cache Management Settings ────────────────────────────
            Text(
                text = s.sectionCache,
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
                            text = s.cacheSize,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatBytes(cacheSize, selectedLang),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = { viewModel.clearCache() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("clear_cache_button")
                    ) {
                        Text(s.clearCache)
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
                        text = s.cacheLimitLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${cacheLimitMb.toInt()} ${s.cacheLimitUnit}",
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
                    text = s.cacheLimitHint,
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
                        text = s.cacheExpirationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${expirationDays.toInt()} ${s.cacheExpirationUnit}",
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
                    text = s.cacheExpirationHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            HorizontalDivider()

            // --- Sound settings -------------------------------------------------
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

            val errorSectionTitle = if (selectedLang == Lang.EN) "Error sound" else "Звук при ошибке"
            val successSectionTitle = if (selectedLang == Lang.EN) "Success sound" else "Звук при успешной отправке"
            val soundLangCode = if (selectedLang == Lang.EN) "en" else "ru"

            SoundPickerSection(
                title = errorSectionTitle,
                langCode = soundLangCode,
                selected = errorSound,
                volume = errorVolume,
                testTagPrefix = "error_sound",
                onSelect = { errorSound = it },
                onVolumeChange = { errorVolume = it.coerceIn(0f, 1f) }
            )

            HorizontalDivider()

            SoundPickerSection(
                title = successSectionTitle,
                langCode = soundLangCode,
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
                    val langCode = if (selectedLang == Lang.EN) "en" else "ru"
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
                    store.setLanguage(langCode)
                    LocaleHelper.applyLocale(context, langCode)
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
                    onBack(langCode)
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth().testTag("save_settings_button")
            ) {
                Text(s.saveAndReturn)
            }
        }
    }
}

@Composable
private fun SettingsLanguageCard(
    flag: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFE2E8F0)
    val bgColor = if (selected) Color(0xFFEFF6FF) else Color.White

    Card(
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = flag, fontSize = 24.sp)
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1E293B)
                )
            )
        }
    }
}

private fun formatBytes(bytes: Long, lang: Lang): String {
    val units = if (lang == Lang.EN) arrayOf("B", "KB", "MB", "GB") else arrayOf("Б", "КБ", "МБ", "ГБ")
    if (bytes <= 0) return "0 ${units[0]}"
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) return "$bytes ${units[0]}"
    return String.format(java.util.Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
