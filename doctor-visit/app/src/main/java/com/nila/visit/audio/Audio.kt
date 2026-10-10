package com.nila.visit.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** 16 kHz mono PCM, as floats in [-1, 1]. Everything Whisper takes. */
object Audio {
    const val RATE = 16_000

    /**
     * Read a 16-bit PCM WAV. Mono and 16 kHz are expected (tools/make_audio.py
     * and the recorder both write that); anything else is converted crudely --
     * channels averaged, sample rate stepped by linear interpolation -- which is
     * fine for speech and keeps this free of a resampling dependency.
     */
    fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV: $file" }
        var pos = 12
        var channels = 1; var rate = RATE; var bits = 16
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataStart = pos + 8; dataLen = minOf(len, bytes.size - dataStart); break
            }
            pos += 8 + len + (len and 1)
        }
        require(dataStart > 0 && bits == 16) { "unsupported WAV ($bits bit)" }
        val frames = dataLen / (2 * channels)
        val mono = FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += bb.getShort(dataStart + (f * channels + c) * 2) / 32768f
            s / channels
        }
        return if (rate == RATE) mono else resample(mono, rate, RATE)
    }

    fun writeWav(file: File, pcm: ShortArray, count: Int = pcm.size) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(count * 2)
            f.write(header.array())
            val body = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) body.putShort(pcm[i])
            f.write(body.array())
        }
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * from / to
            val a = p.toInt().coerceAtMost(x.size - 1); val b = (a + 1).coerceAtMost(x.size - 1)
            val t = (p - a).toFloat()
            x[a] * (1 - t) + x[b] * t
        }
    }

    /**
     * Cut speech into pieces Whisper can take, at the quietest moment.
     *
     * Whisper reads thirty seconds at a time. Cutting at exactly thirty seconds
     * splits a word in half and both halves are usually lost -- and in a
     * doctor's instructions the lost word is as likely as not "not". So each cut
     * is placed at the lowest-energy 100 ms window between 18 and 29 seconds,
     * which for normal speech is a pause between phrases.
     */
    fun segment(audio: FloatArray, maxSeconds: Int = 29, minSeconds: Int = 18): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val win = RATE / 10
        var start = 0
        while (audio.size - start > maxSeconds * RATE) {
            val lo = start + minSeconds * RATE
            val hi = start + maxSeconds * RATE - win
            var best = hi; var bestE = Double.MAX_VALUE
            var p = lo
            while (p <= hi) {
                var e = 0.0
                for (i in p until p + win) e += abs(audio[i]).toDouble()
                if (e < bestE) { bestE = e; best = p }
                p += win / 2
            }
            val cut = best + win / 2
            out += audio.copyOfRange(start, cut)
            start = cut
        }
        if (audio.size - start > RATE / 2) out += audio.copyOfRange(start, audio.size)
        return out
    }

    /**
     * Cut at the speaker's own pauses, then pack whole phrases into pieces.
     *
     * Whisper tends to end a piece at the first full stop: on a Hindi clip it
     * transcribed "the baby has a viral fever, nothing to worry about" and
     * stopped, dropping the next sentence -- the one with the medicine in it.
     * Pieces made of whole phrases lose nothing to that habit, because there is
     * no half-heard phrase at the end for it to give up on.
     *
     * A pause is 250 ms of 20 ms frames below a quarter of the median frame
     * energy -- relative, so it works for a quiet clinic and a loud one alike.
     * Phrases are packed until the next would pass [maxSeconds]; a phrase
     * longer than that on its own falls back to [segment].
     */
    fun segmentByPauses(audio: FloatArray, maxSeconds: Int): List<FloatArray> {
        val frame = RATE / 50
        val n = audio.size / frame
        if (n < 10) return listOf(audio)
        val energy = DoubleArray(n) { f ->
            var e = 0.0
            for (i in f * frame until (f + 1) * frame) e += (audio[i] * audio[i]).toDouble()
            e / frame
        }
        val quiet = energy.sorted()[n / 2] * 0.25
        val minPause = 250 / 20
        // Phrase boundaries: the middle of each long-enough quiet run.
        val cuts = ArrayList<Int>()
        var run = 0
        for (f in 0 until n) {
            if (energy[f] < quiet) run++ else {
                if (run >= minPause) cuts += (f - run / 2) * frame
                run = 0
            }
        }
        val bounds = listOf(0) + cuts + listOf(audio.size)
        val phrases = bounds.zipWithNext { a, b -> a to b }.filter { (a, b) -> b - a > RATE / 5 }
        val out = ArrayList<FloatArray>()
        var start = -1; var end = -1
        val max = maxSeconds * RATE
        for ((a, b) in phrases) {
            if (start < 0) { start = a; end = b; continue }
            if (b - start <= max) end = b
            else {
                out += pack(audio, start, end, maxSeconds)
                start = a; end = b
            }
        }
        if (start >= 0) out += pack(audio, start, end, maxSeconds)
        return out
    }

    private fun pack(audio: FloatArray, a: Int, b: Int, maxSeconds: Int): List<FloatArray> {
        val piece = audio.copyOfRange(a, b)
        return if (piece.size <= maxSeconds * RATE) listOf(piece)
        else segment(piece, maxSeconds, (maxSeconds * 0.6).toInt())
    }

    /**
     * Bring a recording up to a working level before anything reads it.
     *
     * A phone held at arm's length records speech twenty times quieter than
     * the synthetic test files: the first real recording peaked at 0.027 and
     * averaged 0.005, and the silence check -- then absolute -- threw all of it
     * away, so the notes came back empty. Peak-normalised to 0.9, capped at
     * 30x so a silent room is not turned into loud hiss.
     */
    fun normalise(audio: FloatArray): FloatArray {
        var peak = 0f
        for (v in audio) { val a = abs(v); if (a > peak) peak = a }
        if (peak <= 0f) return audio
        val gain = minOf(0.9f / peak, 30f)
        if (gain <= 1.05f) return audio
        return FloatArray(audio.size) { (audio[it] * gain).coerceIn(-1f, 1f) }
    }

    /** True when a piece is too quiet to be worth the encoder's time (after [normalise]). */
    fun isSilent(chunk: FloatArray, threshold: Float = 0.004f): Boolean {
        if (chunk.isEmpty()) return true
        var e = 0.0
        for (v in chunk) e += (v * v).toDouble()
        return kotlin.math.sqrt(e / chunk.size) < threshold
    }
}
