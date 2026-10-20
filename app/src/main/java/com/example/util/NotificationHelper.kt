package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.example.R
import com.example.data.SettingsStore

private const val CHANNEL_PROGRESS = "download_progress"
private const val CHANNEL_SUCCESS = "success_silent_channel"
private const val CHANNEL_ERROR = "error_silent_channel"

private const val NOTIF_ID_SUCCESS = 1001
private const val NOTIF_ID_ERROR = 1002

object NotificationHelper {

    /**
     * Channels are immutable once created, so re-registering one is pure overhead.
     * `createProgressNotification` runs on every throttled progress emit — four times
     * a second for the whole download — and each call used to be a `NotificationChannel`
     * allocation plus a binder IPC to the notification service. A few hundred redundant
     * registrations per download, none of which could change anything.
     */
    @Volatile
    private var channelsCreated = false

    private fun ensureChannels(context: Context) {
        if (channelsCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        synchronized(this) {
            if (channelsCreated) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.notif_channel_progress),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_progress_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
            // The success and error channels are always silent: the alert is played
            // through AlertSoundPlayer so a user-chosen sound and volume are honoured
            // (a channel's sound and volume are fixed at creation time).
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SUCCESS,
                    context.getString(R.string.notif_channel_success),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_success_desc)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ERROR,
                    context.getString(R.string.notif_channel_error),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_error_desc)
                    setSound(null, null)
                    enableLights(true)
                    enableVibration(true)
                }
            )
            channelsCreated = true
        }
    }

    // ------------------------------------------------------------ foreground

    /**
     * Foreground notification for the active download. Required so the OS does not
     * reclaim the worker mid-transfer.
     *
     * [notificationId] is derived from the history row so that two concurrent
     * downloads do not overwrite each other's foreground notification.
     */
    fun createProgressNotification(
        context: Context,
        notificationId: Int,
        status: String,
        progress: Float,
        indeterminate: Boolean
    ): ForegroundInfo {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_progress_title))
            .setContentText(status)
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    // --------------------------------------------------------------- results

    fun showSuccessNotification(
        context: Context,
        title: String,
        message: String,
        sound: AlertSound = AlertSound.None,
        volume: Float = 0f
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(
            NOTIF_ID_SUCCESS,
            NotificationCompat.Builder(context, CHANNEL_SUCCESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
        )

        AlertSoundPlayer.play(context, sound, volume)
    }

    /**
     * Error notification. The channel itself is silent and the sound is played
     * manually via [AlertSoundPlayer] so the user-defined volume is honoured
     * (channel volume cannot be changed once the channel is created).
     *
     * The channel id is fixed rather than derived from the chosen sound: the channel
     * is always created silent, so a per-sound id bought nothing and produced an
     * unbounded pile of dead channels once sounds could be user-supplied.
     */
    fun showErrorNotification(context: Context, title: String, message: String) {
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val settings = SettingsStore(context)

        manager.notify(
            NOTIF_ID_ERROR,
            NotificationCompat.Builder(context, CHANNEL_ERROR)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
        )

        AlertSoundPlayer.play(
            context,
            AlertSound.parse(settings.getErrorSound()),
            settings.getErrorSoundVolume()
        )
    }
}