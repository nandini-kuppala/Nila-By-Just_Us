package com.nila.vision.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.nila.R

/**
 * A soft lullaby that plays together with the light alert (and on "Distract"), to draw the baby's
 * attention. Ported unchanged from Baby Guard CCTV.
 *
 * - The tune is a real audio file, `res/raw/lullaby.wav` (music-box "Twinkle Twinkle Little Star", public
 *   domain), normalised to -1 dBFS peak so the volume range is usable.
 * - Plays on the MEDIA stream: the phone's media volume decides how loud it is. The app never changes the
 *   volume. At media volume 0 it is silent, by design.
 * - Asks for transient audio focus, so other music on the phone pauses while the alert plays.
 */
class AlertMusic(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    private val focus =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var player: MediaPlayer? = null

    @Volatile var playing = false
        private set

    /** Load the file ahead of time (off the main thread), so an alert starts the music at once. */
    @Synchronized
    fun prepare() {
        if (player != null) return
        try {
            val audioSession = audio.generateAudioSessionId()
            val p = MediaPlayer.create(context, R.raw.lullaby, attrs, audioSession)
            if (p == null) {
                log("MUSIC could not load res/raw/lullaby.wav")
                return
            }
            p.isLooping = true
            p.setVolume(1f, 1f)
            p.setOnErrorListener { _, what, extra ->
                log("MUSIC player error what=$what extra=$extra")
                true
            }
            player = p
            log("MUSIC ready: lullaby.wav ${p.duration / 1000.0} s, looping")
        } catch (e: Exception) {
            log("MUSIC prepare failed: ${e.message}")
        }
    }

    fun start() {
        if (playing) return
        prepare()
        val p = player ?: return
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        try {
            p.seekTo(0)
            p.start()
            playing = true
            log(
                "MUSIC on: lullaby at the phone's media volume $vol/$max, focus=${if (granted) "granted" else "refused"}" +
                    if (vol == 0) " (media volume is 0, so it is silent: raise the media volume)" else "",
            )
            // Proof that sound is really going out: the play position must move.
            main.postDelayed({ if (playing) log("MUSIC check: playing=${p.isPlaying} position_ms=${p.currentPosition}") }, 1_500)
        } catch (e: Exception) {
            log("MUSIC start failed: ${e.message}")
        }
    }

    fun stop() {
        if (!playing) return
        playing = false
        runCatching { player?.pause() }
        audio.abandonAudioFocusRequest(focus)
        log("MUSIC off")
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }
}
