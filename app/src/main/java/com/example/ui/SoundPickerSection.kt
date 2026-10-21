package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.util.AlertSound
import com.example.util.AlertSoundPlayer
import com.example.util.CustomSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sound picker for one alert kind (error, success).
 *
 * Custom sounds are copied into private storage on import — see
 * [CustomSounds.import] — so this only has to hold the resulting file name.
 */
@Composable
fun SoundPickerSection(
    title: String,
    langCode: String,
    selected: AlertSound,
    volume: Float,
    testTagPrefix: String,
    onSelect: (AlertSound) -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    // Resource-based strings (`stringResource`, `builtInLabel`) resolve against the
    // current Resources locale, which the parent applies before this recomposes —
    // but only if this composable actually re-executes, so `langCode` is a required
    // input that changes whenever the picked language changes.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var errorMessageText by remember { mutableStateOf<String?>(null) }
    var errorMessageResId by remember { mutableIntStateOf(0) }
    val errorMessage: String? = errorMessageText ?: errorMessageResId.takeIf { it != 0 }
        ?.let { context.resources.getString(it) }
    var missingSound by remember { mutableStateOf(false) }

    // Re-check on every recomposition that changes the selection, so a file deleted
    // outside the app is reported instead of silently producing no sound at all.
    LaunchedEffect(selected) {
        val gone = selected is AlertSound.Custom &&
            CustomSounds.file(context, selected.fileName) == null
        if (gone) {
            missingSound = true
            onSelect(AlertSound.None)
        } else if (!gone) {
            missingSound = false
        }
    }

    // Keyed on an explicit epoch, not on `selected`: re-selecting the same sound is a
    // no-op state write, so a list keyed on it stayed stale after a delete and kept
    // offering a file that was no longer there.
    var importEpoch by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            errorMessageText = null
            errorMessageResId = 0
            runCatching { withContext(Dispatchers.IO) { CustomSounds.import(context, uri) } }
                .onSuccess { fileName ->
                    importEpoch++
                    onSelect(AlertSound.Custom(fileName))
                    AlertSoundPlayer.play(context, AlertSound.Custom(fileName), volume)
                }
                .onFailure {
                    val message = it.message
                    if (message != null) errorMessageText = message
                    else errorMessageResId = com.example.R.string.sound_import_failed
                }
        }
    }

    val imported = remember(importEpoch) { CustomSounds.listFileNames(context) }

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    if (missingSound) {
        Text(
            text = stringResource(com.example.R.string.sound_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    SoundOptionRow(
        label = stringResource(com.example.R.string.sound_none),
        selected = selected is AlertSound.None,
        onSelect = { onSelect(AlertSound.None) },
        testTag = "${testTagPrefix}_none"
    )

    AlertSound.BUILT_IN_KEYS.forEach { key ->
        val resId = AlertSound.builtInRes(key)
        SoundOptionRow(
            label = AlertSound.builtInLabel(context, key),
            selected = selected is AlertSound.BuiltIn && selected.key == key,
            onSelect = { onSelect(AlertSound.builtin(key)) },
            testTag = "${testTagPrefix}_$key",
            onListen = resId?.let { { AlertSoundPlayer.playBuiltIn(context, it, volume) } }
        )
    }

    // --- user-imported sounds -------------------------------------------------
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(com.example.R.string.sound_own_section),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        TextButton(
            onClick = { picker.launch(arrayOf("audio/*")) },
            modifier = Modifier.testTag("${testTagPrefix}_pick")
        ) {
            Text(stringResource(com.example.R.string.sound_pick_own))
        }
    }

    if (imported.isEmpty()) {
        Text(
            text = stringResource(com.example.R.string.sound_own_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    } else {
        imported.forEach { fileName ->
            val isSelected = selected is AlertSound.Custom && selected.fileName == fileName
            SoundOptionRow(
                label = CustomSounds.displayName(context, fileName),
                selected = isSelected,
                onSelect = { onSelect(AlertSound.Custom(fileName)) },
                testTag = "${testTagPrefix}_custom_$fileName",
                onListen = { AlertSoundPlayer.play(context, AlertSound.Custom(fileName), volume) },
                onDelete = {
                    if (isSelected) {
                        errorMessageResId = com.example.R.string.sound_in_use
                    } else {
                        CustomSounds.delete(context, fileName)
                        importEpoch++
                    }
                }
            )
        }
    }

    errorMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    // --- volume ---------------------------------------------------------------
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(com.example.R.string.sound_volume),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "${(volume * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Slider(
        value = volume,
        onValueChange = onVolumeChange,
        valueRange = 0f..1f,
        modifier = Modifier.testTag("${testTagPrefix}_volume")
    )
}

@Composable
private fun SoundOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
    onListen: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.testTag("${testTag}_radio"))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        if (onListen != null) {
            IconButton(onClick = onListen, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(com.example.R.string.sound_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}