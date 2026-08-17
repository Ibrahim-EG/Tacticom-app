package com.familyintercom.tacticom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

/**
 * Everything physically felt/heard for a ring lives here: an Android
 * notification (with its own STOP RINGING action button), repeated
 * vibration, and a looping ringtone through MediaPlayer.
 *
 * This is the direct replacement for the Termux version's shell-outs to
 * termux-notification / termux-vibrate / termux-media-player -- same
 * idea, but as first-class Android APIs instead of subprocess calls,
 * which is both simpler and more reliable (MediaPlayer.isLooping=true is
 * a real loop; Termux's version had to fake one by restarting playback).
 */
class RingController(private val context: Context) {

    companion object {
        private const val TAG = "RingController"
        const val CHANNEL_ID = "tacticom_ring"
        const val NOTIFICATION_ID = 4242
        const val ACTION_STOP_RING = "com.familyintercom.tacticom.ACTION_STOP_RING"
        const val PREFS_NAME = "tacticom_prefs"
        const val PREF_RINGTONE_URI = "ringtone_uri"

        private const val VIBRATE_INTERVAL_MS = 1200L
        private const val VIBRATE_BUZZ_MS = 900L
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var ringThread: Thread? = null
    @Volatile private var stopRequested = false

    fun createNotificationChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ring alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Loud alert when someone in the house rings you"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Lets the user override the default ringtone with any audio file
     * from their device (picked in MainActivity via the system file
     * picker). Falls back to the phone's normal ringtone if unset. */
    fun setCustomRingtoneUri(uri: Uri?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (uri != null) putString(PREF_RINGTONE_URI, uri.toString()) else remove(PREF_RINGTONE_URI)
        }
    }

    fun getCustomRingtoneUri(): Uri? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_RINGTONE_URI, null) ?: return null
        return Uri.parse(stored)
    }

    /**
     * Starts the physical ring in the background and calls [onStop] with
     * "timeout" or "stopped" once it's actually over. Safe to call [stop]
     * from any thread while this is running.
     */
    fun ring(ringer: String, durationMs: Long, onStop: (reason: String) -> Unit) {
        stopRequested = false
        showNotification(ringer)
        startMusic()

        ringThread = Thread {
            var elapsed = 0L
            var reason = "timeout"
            while (elapsed < durationMs) {
                if (stopRequested) {
                    reason = "stopped"
                    break
                }
                buzzOnce()
                try {
                    Thread.sleep(VIBRATE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    reason = "stopped"
                    break
                }
                elapsed += VIBRATE_INTERVAL_MS
            }
            stopMusic()
            clearNotification()
            onStop(reason)
        }.also { it.start() }
    }

    fun stop() {
        stopRequested = true
        ringThread?.interrupt()
    }

    private fun buzzOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATE_BUZZ_MS)
        }
    }

    private fun startMusic() {
        try {
            val uri = getCustomRingtoneUri()
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: return
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ring music (vibration still runs)", e)
        }
    }

    private fun stopMusic() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: Exception) {
                // already stopped/released; ignore
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun showNotification(ringer: String) {
        val stopIntent = Intent(context, TacticomService::class.java).apply { action = ACTION_STOP_RING }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("TACTICOM — Incoming Ring")
            .setContentText("$ringer wants your attention")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_pause, "STOP RINGING", stopPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun clearNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
