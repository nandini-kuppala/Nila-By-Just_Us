package com.nila.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Plays back the clip of a cry episode.
 *
 * The point of it is verification. The summary above it says *probably tired, we
 * played white noise, it did not help, we woke you* -- and a parent who cannot
 * hear what the app heard has to take that whole chain on trust, including a
 * cause estimate the app itself labels unreliable. Thirty seconds of listening
 * settles it either way.
 *
 * The envelope drawn behind the scrubber is the one the escalation ladder
 * actually read, not a decorative waveform: the same numbers that decided
 * whether a soother helped. Tapping it seeks.
 */
@Composable
fun CryClipPlayer(
    path: String,
    /** Normalised loudness, 0..100, as the pipeline measured it. */
    envelope: List<Int>,
    modifier: Modifier = Modifier,
) {
    val file = remember(path) { File(path) }
    if (!file.exists()) {
        Text(
            "The clip for this episode is no longer on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val scope = rememberCoroutineScope()
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var opening by remember(path) { mutableStateOf(false) }
    var positionMs by remember(path) { mutableStateOf(0) }
    var pendingSeekMs by remember(path) { mutableStateOf(0) }
    var failed by remember(path) { mutableStateOf(false) }

    /**
     * Length read from the WAV header rather than from a prepared player.
     *
     * This is why nothing is decoded until somebody presses play. The clips are
     * all 16 kHz mono PCM16 written by [com.nila.audio.EpisodeRecorder], so the
     * duration is the data chunk divided by the byte rate -- one `length()`
     * call, no codec, no I/O on the composition thread.
     */
    val durationMs = remember(path) { wavDurationMs(file) }

    DisposableEffect(path) {
        onDispose {
            // Released rather than paused: this is 90 s of PCM behind a card
            // the reader has finished with, and a leaked MediaPlayer holds an
            // audio focus handle the soothing sounds then have to fight.
            player?.let { p ->
                runCatching { p.setOnCompletionListener(null) }
                runCatching { p.stop() }
                runCatching { p.release() }
            }
            player = null
        }
    }

    // Polled rather than pushed: MediaPlayer has no position callback, and at
    // 20 Hz the scrubber tracks the audio closely enough that nobody can see
    // the difference.
    LaunchedEffect(playing, path) {
        while (playing) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            kotlinx.coroutines.delay(50)
        }
    }

    /**
     * Open the file on first press, not on first draw.
     *
     * The player used to be built in a `remember`, which put `setDataSource`
     * and a synchronous `prepare()` on the main thread during composition --
     * for every clip that scrolled into view, every time it scrolled back.
     * In a timeline with several kept recordings that is a codec handle and a
     * file open per item per pass, and it is what made the list stutter.
     *
     * Nothing about the card needs it: the envelope is drawn from numbers the
     * pipeline already measured and the length comes from the header. The
     * decoder is a consequence of pressing play, so it is created there, off
     * the main thread.
     */
    fun toggle() {
        player?.let { p ->
            runCatching {
                if (playing) p.pause() else p.start()
                playing = !playing
            }
            return
        }
        if (opening) return
        opening = true
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        prepare()
                    }
                }.getOrNull()
            }
            opening = false
            if (opened == null) {
                failed = true
                return@launch
            }
            opened.setOnCompletionListener {
                playing = false
                positionMs = 0
                runCatching { opened.seekTo(0) }
            }
            if (pendingSeekMs > 0) runCatching { opened.seekTo(pendingSeekMs) }
            player = opened
            runCatching { opened.start(); playing = true }
        }
    }

    fun seekTo(ms: Int) {
        positionMs = ms
        val p = player
        // Before the file is open there is nothing to seek; the position is
        // remembered and applied the moment it is.
        if (p == null) pendingSeekMs = ms else runCatching { p.seekTo(ms) }
    }

    if (failed) {
        Text(
            "This clip could not be opened.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val scheme = MaterialTheme.colorScheme
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(scheme.primary)
                .pointerInput(path) { detectTapGestures { toggle() } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause
                              else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause the clip"
                                     else "Play the recorded cry",
                tint = scheme.onPrimary,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ClipEnvelope(
                envelope = envelope,
                fraction = fraction,
                onSeek = { f ->
                    if (durationMs > 0) seekTo((f * durationMs).roundToInt())
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    clock(positionMs),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Text(
                    clock(durationMs),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The measured envelope as bars, with a playhead over it. */
@Composable
private fun ClipEnvelope(
    envelope: List<Int>,
    fraction: Float,
    onSeek: (Float) -> Unit,
) {
    val played = MaterialTheme.colorScheme.primary
    val unplayed = MaterialTheme.colorScheme.outlineVariant
    val flat = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (envelope.size < 2) {
            drawLine(flat, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 3f)
            return@Canvas
        }
        // One bar per column of pixels we can afford, resampled from the
        // envelope rather than one bar per sample: a 180-point envelope in a
        // 300 px box would draw sub-pixel bars and read as a solid block.
        val bars = 56
        val barW = w / (bars * 1.7f)
        for (b in 0 until bars) {
            val f = b / (bars - 1f)
            val v = envelope[(f * (envelope.size - 1)).roundToInt()]
                .coerceIn(0, 100) / 100f
            val barH = (h * 0.18f + h * 0.82f * v).coerceAtLeast(2f)
            val x = f * (w - barW)
            drawRoundRect(
                color = if (f <= fraction) played else unplayed,
                topLeft = Offset(x, (h - barH) / 2f),
                size = androidx.compose.ui.geometry.Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

private fun clock(ms: Int): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Milliseconds of audio in a 16 kHz mono PCM16 WAV, from its size alone.
 *
 * Every clip this app writes has that format and a 44-byte canonical header,
 * so the length is arithmetic rather than a decode. Anything unexpected
 * returns zero, which the card renders as a scrubber with no end marker
 * instead of a wrong one.
 */
private fun wavDurationMs(file: java.io.File): Int {
    val bytes = runCatching { file.length() }.getOrDefault(0L) - 44L
    if (bytes <= 0L) return 0
    val bytesPerSecond = com.nila.audio.LogMelFrontend.SAMPLE_RATE * 2L
    return ((bytes * 1000L) / bytesPerSecond).toInt()
}
