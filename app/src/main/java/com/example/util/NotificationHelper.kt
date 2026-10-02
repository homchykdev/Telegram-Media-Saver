package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.SettingsStore

object NotificationHelper {

    private const val SUCCESS_CHANNEL_ID = "success_silent_channel"

    fun showSuccessNotification(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SUCCESS_CHANNEL_ID,
                "Успешная загрузка (Без звука)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления об успешной отправке медиа"
                enableLights(true)
                setSound(null, null) // Explicitly no sound
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, SUCCESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }

    fun showErrorNotification(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val settingsStore = SettingsStore(context)
        val selectedSound = settingsStore.getErrorSound() // "warning" or "alarm"

        val soundResId = if (selectedSound == "alarm") {
            R.raw.error_alarm
        } else {
            R.raw.error_warning
        }

        val soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + soundResId)
        val channelId = "error_sound_channel_${selectedSound}_v2"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = if (selectedSound == "alarm") "Ошибка (Сигнал)" else "Ошибка (Предупреждение)"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления об ошибках скачивания и отправки"
                enableLights(true)
                setSound(null, null) // Play sound manually for volume control
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true) // Disable default sound
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1002, notification)
        
        // Play sound with custom volume using MediaPlayer
        try {
            val volume = settingsStore.getErrorSoundVolume()
            if (volume > 0f) {
                val mediaPlayer = android.media.MediaPlayer.create(context, soundUri)
                mediaPlayer.setVolume(volume, volume)
                mediaPlayer.setOnCompletionListener { it.release() }
                mediaPlayer.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
