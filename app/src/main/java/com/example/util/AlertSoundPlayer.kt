package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RawRes

private const val TAG = "AlertSoundPlayer"

/**
 * Plays an [AlertSound] once, at a caller-supplied volume.
 *
 * The sound is deliberately played through [MediaPlayer] rather than through the
 * notification channel: a channel's sound is fixed at creation time, so it could
 * never honour a per-user volume slider or a sound chosen after the channel existed.
 *
 * Every player is driven from the main looper. MediaPlayer delivers its
 * onPrepared/onCompletion/onError callbacks through the Looper of the thread that
 * created it, and alerts are played from the worker's IO dispatcher, which has
 * none — the listeners were never invoked and `release()` never ran, leaking a
 * native player per alert once a sound was enabled. Resolution of the sound (a file
 * stat) stays on the caller's thread; only playback moves to the UI thread.
 *
 * Starting a sound stops the previous one instead of layering it: repeated
 * "прослушать" taps in settings used to pile up to 15 s of overlapping players.
 */
object AlertSoundPlayer {

    /** A user-supplied sound can be an entire song; stop well before it becomes a nuisance. */
    private const val MAX_DURATION_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Stop hook of the playback currently in progress, or null. Main thread only. */
    private var activeStop: Runnable? = null

    fun play(context: Context, sound: AlertSound, volume: Float) {
        if (volume <= 0f) return
        val resolved = AlertSound.resolve(context, sound) ?: return
        playResolved(context.applicationContext, resolved, volume)
    }

    /** Plays a raw bundled resource, used for the "прослушать" button in settings. */
    fun playBuiltIn(context: Context, @RawRes resId: Int, volume: Float) {
        if (volume <= 0f) return
        playResolved(context.applicationContext, AlertSound.Resolved(resId, null), volume)
    }

    private fun playResolved(appContext: Context, resolved: AlertSound.Resolved, volume: Float) {
        val source = resolved.file?.absolutePath
            ?: resolved.resId?.let { "android.resource://${appContext.packageName}/$it" }
            ?: return

        mainHandler.post { startPlayback(appContext, source, resolved.file != null, volume) }
    }

    /** Main thread only. */
    private fun startPlayback(appContext: Context, source: String, isFile: Boolean, volume: Float) {
        activeStop?.run()

        var released = false
        var capRunnable: Runnable? = null

        val player = MediaPlayer()
        val stopRunnable = object : Runnable {
            override fun run() {
                if (released) return
                released = true
                if (activeStop === this) activeStop = null
                capRunnable?.let { mainHandler.removeCallbacks(it) }
                runCatching { if (player.isPlaying) player.stop() }
                runCatching { player.release() }
            }
        }

        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            if (isFile) player.setDataSource(source) else player.setDataSource(appContext, Uri.parse(source))

            // Both the completion callback and the duration-cap runnable can fire for
            // the same playback, and `isPlaying`/`stop`/`release` all throw once the
            // engine is gone. Tearing down exactly once makes the two paths
            // independent of the order they happen to arrive in.
            player.setOnCompletionListener { stopRunnable.run() }
            player.setOnErrorListener { _, _, _ ->
                stopRunnable.run()
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
                        Runnable { stopRunnable.run() }.also {
                            capRunnable = it
                            mainHandler.postDelayed(it, cap)
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Не удалось запустить воспроизведение: ${it.message}")
                    stopRunnable.run()
                }
            }

            activeStop = stopRunnable
            player.prepareAsync()
        } catch (e: Exception) {
            // A missing or corrupt custom sound must never take down the alert itself,
            // and a player that never reached prepareAsync still owns native resources.
            Log.w(TAG, "Не удалось воспроизвести звук: ${e.message}")
            stopRunnable.run()
        }
    }
}
