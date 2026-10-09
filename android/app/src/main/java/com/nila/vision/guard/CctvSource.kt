package com.nila.vision.guard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.util.concurrent.Executor

/**
 * A CCTV / IP camera as the video source, over RTSP. Ported from Baby Guard CCTV. The built-in demo
 * ([DEMO_URL], a looped recording of the Baby Guard simulation bundled in the app) plays through the same path,
 * so the demo needs no laptop or network.
 * Media3 ExoPlayer decodes
 * the stream (H.264 / H.265) into a TextureView on the Watch screen; about 10 times a second a frame is
 * copied out and handed to the watch service, which runs the same pipeline as for the phone camera.
 *
 *  - RTP over TCP (more reliable over Wi-Fi, and needed through `adb reverse` for the laptop simulation).
 *  - Reconnects by itself after an error (Wi-Fi drop, camera reboot), every 3 s, and when the stream ends.
 *  - [feedAgeMs] tells the caller how long no new picture has arrived ("camera feed lost").
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

    /** Called on the main thread when the video size becomes known (the screen fits its view to it). */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    val running get() = player != null

    fun feedAgeMs(now: Long) = if (lastFrameAt == 0L) Long.MAX_VALUE else now - lastFrameAt

    fun start(rtspUrl: String) {
        stop()
        url = rtspUrl
        val rtsp = url.startsWith("rtsp://", ignoreCase = true)
        val p = ExoPlayer.Builder(context).build()
        player = p
        p.volume = 0f // camera audio is not used
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
                    connected = state == Player.STATE_READY
                    if (connected != was) log(if (connected) "CCTV connected: $url" else "CCTV state=$state")
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
        if (rtsp) {
            val source = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8_000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            p.setMediaSource(source)
        } else {
            // A local file or the bundled demo: loop it like a camera that never stops.
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.repeatMode = Player.REPEAT_MODE_ONE
        }
        p.prepare()
        p.playWhenReady = true
        log("CCTV connecting: $url")
        main.post(grab)
    }

    /** Copies the current picture about 10x a second (skips if the pipeline is still busy with the last one). */
    private val grab =
        object : Runnable {
            override fun run() {
                if (player == null) return
                main.postDelayed(this, GRAB_MS)
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

    fun stop() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        connected = false
        lastFrameAt = 0
        busy = false
    }

    companion object {
        private const val GRAB_MS = 100L
        /** The bundled demo recording (the Baby Guard simulation), played on a loop. */
        const val DEMO_URL = "asset:///cctv_demo.mp4"

        /** The laptop simulation (`sim/start_cctv_sim.sh` in baby-guard-cctv, through `adb reverse`). */
        const val LAPTOP_SIM_URL = "rtsp://127.0.0.1:8554/cam"
    }
}
