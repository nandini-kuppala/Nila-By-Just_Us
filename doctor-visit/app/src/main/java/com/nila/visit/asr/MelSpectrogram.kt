package com.nila.visit.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

/**
 * Whisper's input: a log-mel spectrogram of exactly thirty seconds.
 *
 * Step for step what `transformers.WhisperFeatureExtractor` does, because the
 * AI Hub encoder was quantised on features made that way and a model fed
 * slightly different numbers degrades quietly rather than failing:
 *
 *  1. pad or cut the 16 kHz waveform to 480,000 samples;
 *  2. reflect-pad 200 samples each side (`center=True`);
 *  3. 400-sample frames every 160 samples under a periodic Hann window;
 *  4. power spectrum, 201 bins;
 *  5. Slaney mel filterbank (written by tools/make_mel.py), floored at 1e-10;
 *  6. log10, drop the last frame (3,001 -> 3,000);
 *  7. clamp to (max - 8), then (x + 4) / 4.
 *
 * The FFT is JTransforms', which handles 400 (not a power of two) directly. A
 * plain DFT against tables was measured at 0.7 to 5 s per chunk on the phone,
 * longer than the NPU encoder it feeds.
 *
 * @param filters [nMels][201] row-major, as make_mel.py writes them.
 */
class MelSpectrogram(private val filters: FloatArray, val nMels: Int) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val N_SAMPLES = 30 * SAMPLE_RATE
        const val N_FRAMES = N_SAMPLES / HOP          // 3000
        const val N_FREQ = N_FFT / 2 + 1              // 201
    }

    init {
        require(filters.size == nMels * N_FREQ) { "filterbank is ${filters.size}, want ${nMels * N_FREQ}" }
    }

    private val window = FloatArray(N_FFT) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / N_FFT)).toFloat() }
    private val fft = org.jtransforms.fft.FloatFFT_1D(N_FFT.toLong())

    /** Each mel filter is non-zero over a few bins only; skip the rest. */
    private val bandStart = IntArray(nMels) { m -> (0 until N_FREQ).firstOrNull { filters[m * N_FREQ + it] != 0f } ?: 0 }
    private val bandEnd = IntArray(nMels) { m -> ((N_FREQ - 1 downTo 0).firstOrNull { filters[m * N_FREQ + it] != 0f } ?: -1) + 1 }

    /** @return [nMels][3000] row-major, already scaled the way the encoder expects. */
    fun compute(audio: FloatArray): FloatArray {
        val wave = FloatArray(N_SAMPLES)
        System.arraycopy(audio, 0, wave, 0, minOf(audio.size, N_SAMPLES))

        // Reflect padding, numpy's mode="reflect": the edge sample is not repeated.
        val pad = N_FFT / 2
        val padded = FloatArray(N_SAMPLES + 2 * pad)
        System.arraycopy(wave, 0, padded, pad, N_SAMPLES)
        for (i in 0 until pad) {
            padded[pad - 1 - i] = wave[i + 1]
            padded[pad + N_SAMPLES + i] = wave[N_SAMPLES - 2 - i]
        }

        val out = FloatArray(nMels * N_FRAMES)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(N_FREQ)
        var maxLog = -Float.MAX_VALUE
        // Frames that only ever see the zero padding have zero power, so every
        // mel value there is log10(1e-10) = -10: computed once, not 3000 times.
        val lastReal = minOf(N_FRAMES, (minOf(audio.size, N_SAMPLES) + pad) / HOP + 1)
        for (t in lastReal until N_FRAMES) for (m in 0 until nMels) out[m * N_FRAMES + t] = -10f
        if (lastReal < N_FRAMES) maxLog = -10f
        for (t in 0 until lastReal) {                 // the 3,001st frame is dropped anyway
            val start = t * HOP
            for (n in 0 until N_FFT) frame[n] = padded[start + n] * window[n]
            // Real-input FFT, packed: [re0, re(n/2), re1, im1, re2, im2, ...].
            fft.realForward(frame)
            power[0] = frame[0] * frame[0]
            power[N_FREQ - 1] = frame[1] * frame[1]
            for (k in 1 until N_FREQ - 1) {
                val re = frame[2 * k]; val im = frame[2 * k + 1]
                power[k] = re * re + im * im
            }
            for (m in 0 until nMels) {
                var acc = 0f
                val row = m * N_FREQ
                for (k in bandStart[m] until bandEnd[m]) acc += filters[row + k] * power[k]
                val v = log10(max(acc, 1e-10f))
                out[m * N_FRAMES + t] = v
                if (v > maxLog) maxLog = v
            }
        }
        val floor = maxLog - 8f
        for (i in out.indices) out[i] = (max(out[i], floor) + 4f) / 4f
        return out
    }
}
