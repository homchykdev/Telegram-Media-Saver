package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.util.AlertSound
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide access to the encrypted settings file.
 *
 * Two things this exists to avoid, both paid on the main thread before:
 *
 *  - **Re-opening the store.** `EncryptedSharedPreferences.create` runs a Keystore
 *    round trip and re-reads and decrypts two files on every construction. The app
 *    built three separate instances over the same file (the ViewModel, each worker run,
 *    and every error notification), so a cold start paid it several times.
 *  - **Re-decrypting values.** `getString` decrypts with AES-SIV/GCM on *every* call.
 *    Settings are read during composition and on every Send tap, so the values are
 *    decrypted once and served from memory afterwards; every write updates that copy.
 */
private object SecureSettings {

    @Volatile
    private var store: SharedPreferences? = null

    private val decrypted = ConcurrentHashMap<String, Any>()

    private fun prefs(context: Context): SharedPreferences =
        store ?: synchronized(this) {
            store ?: EncryptedSharedPreferences.create(
                context.applicationContext,
                "secure_settings",
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { store = it }
        }

    fun getString(context: Context, key: String, default: String): String =
        (decrypted[key] as? String)
            ?: prefs(context).getString(key, default).orEmpty().also { decrypted[key] = it }

    fun getInt(context: Context, key: String, default: Int): Int =
        (decrypted[key] as? Int)
            ?: prefs(context).getInt(key, default).also { decrypted[key] = it }

    fun getFloat(context: Context, key: String, default: Float): Float =
        (decrypted[key] as? Float)
            ?: prefs(context).getFloat(key, default).also { decrypted[key] = it }

    /** Writes through to disk and updates the in-memory copy in one step. */
    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
        decrypted[key] = value
    }

    fun put(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
        decrypted[key] = value
    }

    fun put(context: Context, key: String, value: Float) {
        prefs(context).edit().putFloat(key, value).apply()
        decrypted[key] = value
    }
}

class SettingsStore(private val context: Context) {

    /** True after the user has completed or explicitly skipped first-launch setup. */
    fun isOnboardingComplete(): Boolean =
        SecureSettings.getInt(context, "onboarding_complete", 0) != 0

    fun setOnboardingComplete() =
        SecureSettings.put(context, "onboarding_complete", 1)

    fun getBotToken(): String = SecureSettings.getString(context, "bot_token", "")
    fun setBotToken(token: String) = SecureSettings.put(context, "bot_token", token.trim())

    fun getGroupId(): String = SecureSettings.getString(context, "group_id", "")
    fun setGroupId(groupId: String) = SecureSettings.put(context, "group_id", groupId.trim())

    fun getCacheLimitMb(): Int = SecureSettings.getInt(context, "cache_limit_mb", 500)
    fun setCacheLimitMb(limit: Int) = SecureSettings.put(context, "cache_limit_mb", limit)

    fun getCacheExpirationDays(): Int = SecureSettings.getInt(context, "cache_expiration_days", 7)
    fun setCacheExpirationDays(days: Int) = SecureSettings.put(context, "cache_expiration_days", days)

    fun getErrorSound(): String =
        SecureSettings.getString(context, "error_sound", AlertSound.KEY_WARNING)

    fun setErrorSound(sound: String) = SecureSettings.put(context, "error_sound", sound)

    fun getErrorSoundVolume(): Float = SecureSettings.getFloat(context, "error_sound_volume", 1.0f)
    fun setErrorSoundVolume(volume: Float) = SecureSettings.put(context, "error_sound_volume", volume)

    /**
     * Success is silent by default: previously the app only ever made noise on
     * failure, and turning that on should be an explicit choice rather than a
     * behaviour change on upgrade.
     */
    fun getSuccessSound(): String = SecureSettings.getString(context, "success_sound", AlertSound.KEY_NONE)
    fun setSuccessSound(sound: String) = SecureSettings.put(context, "success_sound", sound)

    fun getSuccessSoundVolume(): Float = SecureSettings.getFloat(context, "success_sound_volume", 1.0f)
    fun setSuccessSoundVolume(volume: Float) = SecureSettings.put(context, "success_sound_volume", volume)

    /** Every stored sound value, used to garbage-collect unreferenced imports. */
    fun storedSoundValues(): List<String> = listOf(getErrorSound(), getSuccessSound())
}