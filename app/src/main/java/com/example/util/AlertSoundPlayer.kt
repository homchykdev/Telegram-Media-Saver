package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.annotation.RawRes

private const val TAG = "AlertSoundPlayer"

/**
 * Plays an [AlertSound] once, at a caller-supplied volume.
 *
 * The sound is deliberately played through [MediaPlayer] rather than through the
 * notification channel: a channel's sound is fixed at creation time, so it could
 * never honour a per-user volume slider or a sound chosen after the channel existed.
 */
object AlertSoundPlayer {

    /** A user-supplied sound can be an entire song; stop well before it becomes a nuisance. */
    private const val MAX_DURATION_MS = 15_000L

    fun play(context: Context, sound: AlertSound, volume: Float) {
        if (volume <= 0f) return
        val resolved = AlertSound.resolve(context, sound) ?: return
        playResolved(context, resolved, volume)
    }

    /** Plays a raw bundled resource, used for the "прослушать" button in settings. */
    fun playBuiltIn(context: Context, @RawRes resId: Int, volume: Float) {
        if (volume <= 0f) return
        playResolved(context, AlertSound.Resolved(resId, null), volume)
    }

    private fun playResolved(context: Context, resolved: AlertSound.Resolved, volume: Float) {
        val appContext = context.applicationContext

        val player = MediaPlayer()
        // Both the completion callback and the duration-cap runnable can fire for the
        // same playback, and `isPlaying`/`stop`/`release` all throw once the engine is
        // gone. Tearing down exactly once makes the two paths independent of the
        // order they happen to arrive in.
        var released = false
        fun release() {
            if (released) return
            released = true
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val source = resolved.file?.absolutePath
                ?: resolved.resId?.let { "android.resource://${appContext.packageName}/$it" }
                ?: return
            if (resolved.file != null) player.setDataSource(source) else player.setDataSource(appContext, Uri.parse(source))

            player.setOnCompletionListener { release() }
            player.setOnErrorListener { _, _, _ ->
                release()
                true
            }

            // A user-supplied file can be several megabytes; the synchronous prepare()
            // would block the main thread for as long as the decoder needs.
            player.setOnPreparedListener { prepared ->
                runCatching {
                    prepared.setVolume(volume, volume)
                    prepared.start()
                    val cap = minOf(prepared.duration.toLong(), MAX_DURATION_MS)
                    if (cap in 1 until MAX_DURATION_MS) {
                        Handler(appContext.mainLooper).postDelayed({ release() }, cap)
                    }
                }.onFailure {
                    Log.w(TAG, "Не удалось запустить воспроизведение: ${it.message}")
                    release()
                }
            }
            player.prepareAsync()
        } catch (e: Exception) {
            // A missing or corrupt custom sound must never take down the alert itself.
            Log.w(TAG, "Не удалось воспроизвести звук: ${e.message}")
            release()
        }
    }
}
