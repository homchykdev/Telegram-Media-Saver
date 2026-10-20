package com.example.util

import android.content.Context
import com.example.R
import java.io.File

/**
 * A sound the app can play for an alert.
 *
 * Stored in preferences as a single string so that adding a new source never needs a
 * schema change:
 *  - `"none"`
 *  - `"builtin:<key>"` — one of the bundled files in `res/raw`
 *  - `"custom:<fileName>"` — a file the user imported, kept in the app's private
 *    `files/sounds` directory
 *
 * Custom sounds are *copied* into app storage rather than referenced by their
 * `content://` URI. A picked URI can stop being readable the moment the user
 * revokes access or the provider is reinstalled, and the sound has to keep working
 * from a background [androidx.work.CoroutineWorker] long afterwards.
 */
sealed class AlertSound {

    object None : AlertSound()

    data class BuiltIn(val key: String) : AlertSound()

    /** [fileName] is a bare name inside the sounds directory, never a full path. */
    data class Custom(val fileName: String) : AlertSound()

    val isPlayable: Boolean get() = this !is None

    companion object {
        const val KEY_NONE = "none"
        const val KEY_WARNING = "warning"
        const val KEY_ALARM = "alarm"
        const val KEY_SUCCESS = "success"

        private const val PREFIX_BUILTIN = "builtin:"
        private const val PREFIX_CUSTOM = "custom:"

        val BUILT_IN_KEYS = listOf(KEY_WARNING, KEY_ALARM, KEY_SUCCESS)

        /**
         * Parses a stored preference.
         *
         * A bare key such as `"warning"` predates the `builtin:` prefix and is still
         * present in existing installs, so it is accepted as a built-in rather than
         * silently resetting the user's choice.
         */
        fun parse(raw: String?): AlertSound {
            if (raw.isNullOrBlank() || raw == KEY_NONE) return None
            if (raw.startsWith(PREFIX_CUSTOM)) {
                val name = raw.removePrefix(PREFIX_CUSTOM)
                return if (name.isBlank()) None else Custom(name)
            }
            val key = raw.removePrefix(PREFIX_BUILTIN)
            return if (key in BUILT_IN_KEYS) BuiltIn(key) else None
        }

        fun encode(sound: AlertSound): String = when (sound) {
            is None -> KEY_NONE
            is BuiltIn -> "$PREFIX_BUILTIN${sound.key}"
            is Custom -> "$PREFIX_CUSTOM${sound.fileName}"
        }

        fun builtin(key: String) = BuiltIn(key)

        /** Human-readable name for a bundled sound. */
        fun builtInLabel(context: Context, key: String): String = when (key) {
            KEY_WARNING -> context.getString(R.string.sound_builtin_warning)
            KEY_ALARM -> context.getString(R.string.sound_builtin_alarm)
            KEY_SUCCESS -> context.getString(R.string.sound_builtin_success)
            else -> key
        }

        /** The bundled file for a key, or null when the key is unknown. */
        @androidx.annotation.RawRes
        fun builtInRes(key: String): Int? = when (key) {
            KEY_WARNING -> R.raw.error_warning
            KEY_ALARM -> R.raw.error_alarm
            KEY_SUCCESS -> R.raw.success_chime
            else -> null
        }

        /**
         * Resolves the sound to something playable, or null when the underlying file
         * has gone missing (a custom sound the user deleted outside the app).
         */
        fun resolve(context: Context, sound: AlertSound): Resolved? = when (sound) {
            is None -> null
            is BuiltIn -> builtInRes(sound.key)?.let { Resolved(it, null) }
            is Custom -> CustomSounds.file(context, sound.fileName)
                ?.takeIf { it.length() > 0 }
                ?.let { Resolved(null, it) }
        }
    }

    /** Either a raw resource id or a file on disk — never both. */
    class Resolved(val resId: Int?, val file: File?)
}