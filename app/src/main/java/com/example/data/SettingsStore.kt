package com.example.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getBotToken(): String = prefs.getString("bot_token", "") ?: ""
    fun setBotToken(token: String) = prefs.edit().putString("bot_token", token.trim()).apply()

    fun getGroupId(): String = prefs.getString("group_id", "") ?: ""
    fun setGroupId(groupId: String) = prefs.edit().putString("group_id", groupId.trim()).apply()

    fun getCacheLimitMb(): Int = prefs.getInt("cache_limit_mb", 500)
    fun setCacheLimitMb(limit: Int) = prefs.edit().putInt("cache_limit_mb", limit).apply()

    fun getCacheExpirationDays(): Int = prefs.getInt("cache_expiration_days", 7)
    fun setCacheExpirationDays(days: Int) = prefs.edit().putInt("cache_expiration_days", days).apply()

    fun getErrorSound(): String = prefs.getString("error_sound", "warning") ?: "warning"
    fun setErrorSound(sound: String) = prefs.edit().putString("error_sound", sound).apply()

    fun getErrorSoundVolume(): Float = prefs.getFloat("error_sound_volume", 1.0f)
    fun setErrorSoundVolume(volume: Float) = prefs.edit().putFloat("error_sound_volume", volume).apply()
}
