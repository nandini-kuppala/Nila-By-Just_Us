package com.nila.vision.guard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.util.concurrent.Executor

/**
 * A CCTV / IP camera as the video source, over RTSP. Ported from Baby Guard CCTV. The built-in demo
 * ([DEMO_URL], a looped recording of the Baby Guard simulation bundled in the app) plays through the same path,
 * so the demo runs on the phone alone.
 * Media3 ExoPlayer decodes
 * the stream (H.264 / H.265) into a TextureView on the Watch screen; about 10 times a second a frame is
 * copied out and handed to the watch service, which runs the same pipeline as for the phone camera.
 *
 * A live camera (rtsp://) gets the low-delay setup measured on the CP Plus CP-E41Q (experiments/cpplus-cctv:
 * ~1.16 s glass to glass, the camera's own floor being ~1.0 s):
 *  - RTP over UDP first (the CP-E41Q stalled over TCP every 15-50 s; Media3 falls back to TCP by itself).
 *    TCP only through a USB relay (127.0.0.1), where UDP cannot get through.
 *  - The camera's audio track is not decoded (the player timed the video to the audio output, +~0.8 s).
 *  - Starts after 0.1 s of video instead of ExoPlayer's 2.5 s, and catches up after a hiccup (see [catchUp]).
 *  - Reconnects after an error (every 3 s), when the stream ends, and after 10 s without a picture.
 *  - Keeps the Wi-Fi radio awake (low-latency lock) while the camera is shown: power saving delayed packets
 *    in bursts (ping to the camera 12-989 ms → 24-203 ms with the lock).
 * The demo file keeps the plain setup it was tested with.
 *
 * [feedAgeMs] tells the caller how long no new picture has arrived ("camera feed lost").
 */
@OptIn(UnstableApi::class)
class CctvSource(
    private val context: Context,
    private val view: TextureView,
    private val analysis: Executor,
    private val onFrame: (Bitmap) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var url = ""
    private var live = false

    @Volatile var videoWidth = 0
        private set

    @Volatile var videoHeight = 0
        private set

    @Volatile var connected = false
        private set

    @Volatile private var lastFrameAt = 0L

    @Volatile private var busy = false

    @Volatile var fps = 0f
        private set
    private var count = 0
    private var countStart = 0L

    /** Live camera: when the player last stopped showing pictures (connecting, buffering); 0 while it plays. */
    @Volatile private var notReadySince = 0L

    /** Live camera: video received but not shown yet = extra delay (ms). */
    @Volatile var aheadMs = 0L
        private set
    private var lastAheadLog = 0L
    private var lastPos = 0L
    @Volatile private var rebuffers = 0

    /** Lowest (wall clock - video position) since connecting = the live edge; see [catchUp]. */
    private var minLag = Long.MAX_VALUE
    @Volatile private var rebase = false

    private var wifiLock: WifiManager.WifiLock? = null

    /** Called on the main thread when the video size becomes known (the screen fits its view to it). */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    val running get() = player != null

    fun feedAgeMs(now: Long) = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt

    fun start(rtspUrl: String) {
        stop()
        url = rtspUrl
        live = url.startsWith("rtsp://", ignoreCase = true)
        minLag = Long.MAX_VALUE
        rebase = false
        val p = if (live) {
            // Low delay: start after 0.1 s of video (the default waits for 2.5 s: measured 1.85 s behind). A small
            // cushion (0.15 s after a hiccup) instead of none: with none the player flips playing/buffering many
            // times a second.
            ExoPlayer.Builder(context)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 100, 150).build())
                .build()
        } else {
            ExoPlayer.Builder(context).build()
        }
        player = p
        p.volume = 0f // camera audio is not used
        if (live) {
            p.trackSelectionParameters =
                p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            holdWifi()
        } else {
            releaseWifi()
        }
        p.setVideoTextureView(view)
        p.addListener(
            object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width == 0 || size.height == 0) return
                    videoWidth = size.width
                    videoHeight = size.height
                    log("CCTV video ${size.width}x${size.height}")
                    onVideoSize?.invoke(size.width, size.height)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    val was = connected
                    if (live) {
                        // "connected" = the stream is up: a short BUFFERING (a late packet) keeps it and the last
                        // picture stays on screen; only IDLE / ENDED drop it. A long one reconnects (see grab).
                        if (state == Player.STATE_BUFFERING && was) {
                            rebuffers++
                            if (p.playbackParameters.speed > 1f) rebase = true
                        }
                        when (state) {
                            Player.STATE_READY -> connected = true
                            Player.STATE_IDLE, Player.STATE_ENDED -> connected = false
                        }
                        notReadySince = if (state == Player.STATE_READY) 0L
                        else if (notReadySince == 0L) System.currentTimeMillis() else notReadySince
                    } else {
                        connected = state == Player.STATE_READY
                    }
                    if (connected != was) log(if (connected) "CCTV connected: ${redactUrl(url)}" else "CCTV state=$state")
                    // The camera stopped sending (reboot, publisher restart): RTSP reports the end of the
                    // stream, not an error, so reconnect here too.
                    if (state == Player.STATE_ENDED) {
                        log("CCTV stream ended - reconnecting in 1 s")
                        main.postDelayed({ if (player === p) start(url) }, 1_000)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    connected = false
                    log("CCTV error: ${error.errorCodeName} ${error.message} - retrying in 3 s")
                    main.postDelayed({ if (player === p) start(url) }, 3_000)
                }
            },
        )
        if (live) {
            val relay = Uri.parse(url).host.let { it == "127.0.0.1" || it == "localhost" }
            val source = RtspMediaSource.Factory().setForceUseRtpTcp(relay).setTimeoutMs(8_000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            p.setMediaSource(source)
            log("CCTV connecting: ${redactUrl(url)} (${if (relay) "TCP" else "UDP first"})")
            notReadySince = System.currentTimeMillis()
        } else {
            // A local file or the bundled demo: loop it like a camera that never stops.
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.repeatMode = Player.REPEAT_MODE_ONE
            log("CCTV connecting: $url")
        }
        p.prepare()
        p.playWhenReady = true
        main.post(grab)
    }

    /** Copies the current picture about 10x a second (skips if the pipeline is still busy with the last one). */
    private val grab =
        object : Runnable {
            override fun run() {
                if (player == null) return
                val since = notReadySince
                if (live && since > 0 && System.currentTimeMillis() - since > STALL_MS) {
                    log("CCTV no picture for ${STALL_MS / 1000} s - reconnecting")
                    start(url)
                    return
                }
                main.postDelayed(this, GRAB_MS)
                if (live) catchUp()
                if (!connected || busy || videoWidth == 0 || !view.isAvailable) return
                val w = 640
                val h = (640f * videoHeight / videoWidth).toInt().coerceAtLeast(2)
                val bmp = runCatching { view.getBitmap(w, h) }.getOrNull() ?: return
                busy = true
                val now = System.currentTimeMillis()
                lastFrameAt = now
                count++
                if (countStart == 0L) countStart = now
                if (now - countStart >= 2_000) {
                    fps = count * 1000f / (now - countStart)
                    count = 0
                    countStart = now
                }
                analysis.execute {
                    try {
                        onFrame(bmp)
                    } finally {
                        busy = false
                    }
                }
            }
        }

    /**
     * Live catch-up. ExoPlayer has no live-delay control for RTSP, so every wait (start-up, a Wi-Fi hiccup) would
     * stay as delay for good. lag = wall clock - video position stays constant while the video plays in real time
     * and grows with every hiccup; its lowest value since connecting is the live edge, so lag - minimum is the
     * real extra delay: play at 1.25x above 0.3 s of it, back to 1x under 0.08 s (video only, nothing to hear).
     * (Media3's bufferedPosition is no use here over RTSP: speeding up on it caused ~3 short stalls a second.)
     */
    private fun catchUp() {
        val p = player ?: return
        if (!connected) return
        val now = System.currentTimeMillis()
        val lag = now - p.currentPosition
        if (rebase) {
            // a wait for data while playing faster: there was nothing left to catch up
            minLag = lag
            rebase = false
        }
        minLag = minOf(minLag, lag)
        aheadMs = lag - minLag
        val cur = p.playbackParameters.speed
        val speed = when {
            aheadMs > 300 -> 1.25f
            aheadMs < 80 -> 1f
            else -> cur // keep catching up until under 0.08 s
        }
        if (cur != speed) p.setPlaybackSpeed(speed)
        if (now - lastAheadLog > 10_000) {
            // effective = how fast the shown video really advanced (1.0 = real time); rebuffers = short waits for data
            val pos = p.currentPosition
            val effective = if (lastAheadLog > 0) (pos - lastPos).toFloat() / (now - lastAheadLog) else 0f
            log("CCTV extra_delay_ms=$aheadMs speed=$speed effective=${"%.2f".format(effective)} rebuffers=$rebuffers")
            lastAheadLog = now
            lastPos = pos
            rebuffers = 0
        }
    }

    private fun holdWifi() {
        if (wifiLock?.isHeld == true) return
        wifiLock = runCatching {
            context.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "nila:cctv")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        log("CCTV wifi low-latency lock=${wifiLock?.isHeld}")
    }

    /** [stop] keeps the Wi-Fi lock (a reconnect is stop + start); this also releases it, when the picture goes. */
    fun release() {
        stop()
        releaseWifi()
    }

    private fun releaseWifi() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        connected = false
        lastFrameAt = 0
        busy = false
        notReadySince = 0L
    }

    companion object {
        private const val GRAB_MS = 100L

        /** Live camera: connecting or buffering this long without a picture = stuck stream, start afresh. */
        private const val STALL_MS = 10_000L

        /** The bundled demo recording (the Baby Guard simulation), played on a loop. */
        const val DEMO_URL = "asset:///cctv_demo.mp4"
    }
}
