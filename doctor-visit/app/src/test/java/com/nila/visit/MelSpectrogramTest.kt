package com.nila.visit

import com.nila.visit.asr.MelSpectrogram
import com.nila.visit.audio.Audio
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Kotlin features against numpy's (tools/ref_mel.py) on the same clip.
 * The encoder was quantised on WhisperFeatureExtractor's numbers; a port that
 * drifts from them costs accuracy without ever failing, so it is held here.
 */
class MelSpectrogramTest {

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun `matches the numpy reference`() {
        val res = File(javaClass.classLoader!!.getResource("clip.wav")!!.toURI()).parentFile
        val filters = floats(File(res.parentFile.parentFile, "main/assets/mel_128.bin").takeIf { it.exists() }
            ?: File("src/main/assets/mel_128.bin"))
        val mel = MelSpectrogram(filters, 128).compute(Audio.readWav(File(res, "clip.wav")))
        val ref = floats(File(res, "clip.mel128.bin"))
        var maxDiff = 0f; var sum = 0.0
        for (i in mel.indices) { val d = kotlin.math.abs(mel[i] - ref[i]); sum += d; if (d > maxDiff) maxDiff = d }
        println("mel vs numpy: max |diff| = $maxDiff, mean = ${sum / mel.size}")
        assertTrue("max diff $maxDiff", maxDiff < 2e-3f)
    }
}
