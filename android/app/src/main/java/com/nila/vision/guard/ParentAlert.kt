package com.nila.vision.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.nila.MainActivity
import com.nila.R

/**
 * CCTV mode: the phone is with the parent, the camera is with the baby. Ported from Baby Guard CCTV. On an
 * alert the parent's phone gets, on top of the lights and the flashing screen:
 *  - a high-priority notification ("Go to the baby now"),
 *  - a clear chime (`res/raw/parent_chime.wav`, looped) at the phone's media volume,
 *  - vibration (a repeating pattern; works in silent mode, the parent may have the phone in a pocket).
 * The lullaby is not used in this mode: the baby cannot hear the parent's phone.
 */
class ParentAlert(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private var chime: MediaPlayer? = null

    @Volatile var active = false
        private set

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Baby alert for parents (CCTV)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "CCTV mode: the camera saw the baby in danger or leaving the safe zone"
                enableVibration(false) // the app vibrates itself, with its own pattern
                setSound(null, null) // the app plays its own chime
            },
        )
    }

    private fun openApp() =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun start(reason: String) {
        if (active) return
        active = true
        val parts = mutableListOf<String>()
        val n =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nila)
                .setContentTitle("Go to the baby now")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp())
                .setOngoing(true)
                .build()
        parts += runCatching { notifications.notify(NOTIFICATION_ID, n); "notification=ok" }.getOrElse { "notification=failed(${it.message})" }
        parts +=
            runCatching {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                val p = chime ?: MediaPlayer.create(context, R.raw.parent_chime, attrs, audio.generateAudioSessionId()).also { chime = it }
                p.isLooping = true
                p.seekTo(0)
                p.start()
                "chime=ok(media volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)})"
            }.getOrElse { "chime=failed(${it.message})" }
        // vibration: 0.6 s on, 0.4 s off, repeating
        parts +=
            runCatching {
                val v = vibrator ?: return@runCatching "vibration=none"
                v.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
                "vibration=ok"
            }.getOrElse { "vibration=failed(${it.message})" }
        log("PARENT_ALERT on: $reason | ${parts.joinToString(" ")}")
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { chime?.pause() }
        runCatching { vibrator?.cancel() }
        notifications.cancel(NOTIFICATION_ID)
        log("PARENT_ALERT off")
    }

    /** "Camera feed lost": only a notification (no lights or sound, it is not a baby emergency). */
    fun feedLost(lost: Boolean, text: String = "Nila cannot see the camera. Check the camera and the Wi-Fi.") {
        if (lost) {
            val n =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_nila)
                    .setContentTitle("CCTV camera not reachable")
                    .setContentText(text)
                    .setContentIntent(openApp())
                    .setOngoing(true)
                    .build()
            runCatching { notifications.notify(LOST_ID, n) }
        } else {
            runCatching { notifications.cancel(LOST_ID) }
        }
    }

    fun release() {
        stop()
        feedLost(false)
        chime?.release()
        chime = null
    }

    companion object {
        private const val CHANNEL = "guard_parent_alert"
        private const val NOTIFICATION_ID = 2101
        private const val LOST_ID = 2102
    }
}
