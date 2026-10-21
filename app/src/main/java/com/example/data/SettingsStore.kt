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
 *
 * The remaining cost — creating the store the first time — is moved off the UI
 * thread by [warmUp], which [com.example.MediaSaverApp] calls on a background thread
 * during process start. Only the bot token and the group id are kept here; the rest
 * of the settings were language, cache limits and sounds — nothing secret — and they
 * moved to a plain prefs file so Activity creation and composition never have to
 * wait on a Keystore round trip to know which locale to apply.
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

    /**
     * Opens the store on the caller's thread. Called once per process from a
     * background thread in [com.example.MediaSaverApp.onCreate], so subsequent
     * accesses — including the main thread's — hit the finished object.
     */
    fun warmUp(context: Context) {
        prefs(context)
    }

    fun getString(context: Context, key: String, default: String): String =
        (decrypted[key] as? String)
            ?: prefs(context).getString(key, default).orEmpty().also { decrypted[key] = it }

    /** Writes through to disk and updates the in-memory copy in one step. */
    fun put(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
        decrypted[key] = value
    }

    /** The stored value, or null when the key was never written. Used by [SettingsStore]'s migration. */
    fun getStringIfSet(context: Context, key: String): String? {
        (decrypted[key] as? String)?.let { return it }
        return prefs(context).getString(key, null)?.also { decrypted[key] = it }
    }

    fun getIntIfSet(context: Context, key: String): Int? {
        (decrypted[key] as? Int)?.let { return it }
        val raw = prefs(context)
        if (!raw.contains(key)) return null
        return raw.getInt(key, 0).also { decrypted[key] = it }
    }

    fun getFloatIfSet(context: Context, key: String): Float? {
        (decrypted[key] as? Float)?.let { return it }
        val raw = prefs(context)
        if (!raw.contains(key)) return null
        return raw.getFloat(key, 0f).also { decrypted[key] = it }
    }
}

class SettingsStore(private val context: Context) {

    /**
     * Language, cache limits, sounds and the onboarding flag carry no secret and are
     * read on the main thread (Activity creation, composition, the share sheet), so
     * they live in a plain prefs file that costs nothing to open.
     */
    private val plain: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ plain prefs
    //
    // Values written by earlier versions still sit in the encrypted store; the first
    // read migrates them across one time. The stale encrypted copy is left in place:
    // from then on the plain key is always present and shadows it, and deleting from
    // the encrypted store would force a Keystore round trip on exactly the calls
    // this split exists to avoid.

    private fun migratedInt(key: String, default: Int): Int {
        if (plain.contains(key)) return plain.getInt(key, default)
        val legacy = SecureSettings.getIntIfSet(context, key) ?: return default
        plain.edit().putInt(key, legacy).apply()
        return legacy
    }

    private fun migratedFloat(key: String, default: Float): Float {
        if (plain.contains(key)) return plain.getFloat(key, default)
        val legacy = SecureSettings.getFloatIfSet(context, key) ?: return default
        plain.edit().putFloat(key, legacy).apply()
        return legacy
    }

    private fun migratedString(key: String, default: String): String {
        if (plain.contains(key)) return plain.getString(key, default) ?: default
        val legacy = SecureSettings.getStringIfSet(context, key) ?: return default
        plain.edit().putString(key, legacy).apply()
        return legacy
    }

    /** True after the user has completed or explicitly skipped first-launch setup. */
    fun isOnboardingComplete(): Boolean = migratedInt("onboarding_complete", 0) != 0

    fun setOnboardingComplete() {
        plain.edit().putInt("onboarding_complete", 1).apply()
    }

    // ------------------------------------------------------- encrypted secrets
    //
    // The bot token is the bot's password and the group id identifies a private
    // conversation with it; both stay encrypted.

    fun getBotToken(): String = SecureSettings.getString(context, "bot_token", "")
    fun setBotToken(token: String) = SecureSettings.put(context, "bot_token", token.trim())

    fun getGroupId(): String = SecureSettings.getString(context, "group_id", "")
    fun setGroupId(groupId: String) = SecureSettings.put(context, "group_id", groupId.trim())

    // ------------------------------------------------------------------ plain

    fun getCacheLimitMb(): Int = migratedInt("cache_limit_mb", 500)
    fun setCacheLimitMb(limit: Int) = plain.edit().putInt("cache_limit_mb", limit).apply()

    fun getCacheExpirationDays(): Int = migratedInt("cache_expiration_days", 7)
    fun setCacheExpirationDays(days: Int) = plain.edit().putInt("cache_expiration_days", days).apply()

    fun getErrorSound(): String = migratedString("error_sound", AlertSound.KEY_WARNING)
    fun setErrorSound(sound: String) = plain.edit().putString("error_sound", sound).apply()

    fun getErrorSoundVolume(): Float = migratedFloat("error_sound_volume", 1.0f)
    fun setErrorSoundVolume(volume: Float) = plain.edit().putFloat("error_sound_volume", volume).apply()

    /**
     * Success is silent by default: previously the app only ever made noise on
     * failure, and turning that on should be an explicit choice rather than a
     * behaviour change on upgrade.
     */
    fun getSuccessSound(): String = migratedString("success_sound", AlertSound.KEY_NONE)
    fun setSuccessSound(sound: String) = plain.edit().putString("success_sound", sound).apply()

    fun getSuccessSoundVolume(): Float = migratedFloat("success_sound_volume", 1.0f)
    fun setSuccessSoundVolume(volume: Float) = plain.edit().putFloat("success_sound_volume", volume).apply()

    fun getLanguage(): String = migratedString("app_language", "en")
    fun setLanguage(lang: String) = plain.edit().putString("app_language", lang).apply()

    /** Every stored sound value, used to garbage-collect unreferenced imports. */
    fun storedSoundValues(): List<String> = listOf(getErrorSound(), getSuccessSound())
}

/**
 * Opens the encrypted settings store ahead of any caller who needs it. Called from a
 * background thread in [com.example.MediaSaverApp] so the Keystore round trip never
 * runs on the main thread.
 */
fun warmUpSecureSettings(context: Context) {
    SecureSettings.warmUp(context)
}