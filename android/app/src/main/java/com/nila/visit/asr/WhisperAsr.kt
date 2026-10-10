package com.nila.visit.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper on the Hexagon NPU, as Qualcomm AI Hub compiles it.
 *
 * The bundle is AI Hub's `precompiled_qnn_onnx` export (v0.62.0, QAIRT 2.45):
 * an encoder and a decoder, each a tiny ONNX graph around a QNN context binary
 * that runs on the HTP through ONNX Runtime's QNN execution provider. The
 * wrappers are patched by tools/patch_whisper.py so the uint16 tensors become
 * float -- ONNX Runtime's Java API cannot make uint16 -- and everything else is
 * passed exactly as the bundle defines it.
 *
 * The decode loop is Qualcomm's own (HfWhisperApp._transcribe_single_chunk):
 * greedy, one token per call, a fixed 199-slot self-attention cache filled from
 * the end, cross-attention caches computed once by the encoder. Two things are
 * added for multilingual use: the language is read off the first step's logits
 * (or forced), and the prompt is `<|sot|><|lang|><|transcribe|><|notimestamps|>`
 * rather than a bare start token, which is what keeps Whisper transcribing in
 * the language spoken instead of drifting into English or timestamps.
 */
class WhisperAsr(
    private val modelDir: File,
    private val tokenizer: WhisperTokenizer,
    private val mel: MelSpectrogram,
) : Closeable {

    companion object {
        private const val TAG = "WhisperAsr"
        const val DECODE_LEN = 200
        private const val MASK_NEG = -100f
    }

    data class Chunk(
        val text: String,
        val language: String?,
        val tokens: Int,
        val melMs: Long,
        val encoderMs: Long,
        val decoderMs: Long,
    )

    private val env = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private val cfg = tokenizer.config

    /** Shapes and quantisation of the uint8 self-attention caches, from metadata.json. */
    private data class Cache(val name: String, val shape: LongArray, val zeroPoint: Int)
    private lateinit var selfCaches: List<Cache>

    var loadMs: Long = 0; private set
    var backend: String = "-"; private set

    /**
     * Build both sessions on the HTP. Throws if QNN will not take them: there
     * is no CPU fallback on purpose, because a CPU Whisper-Large on a phone is
     * minutes per chunk and a fallback that silent would read as a hang.
     */
    fun load() {
        if (encoder != null) return
        val started = System.currentTimeMillis()
        val meta = JSONObject(File(modelDir, "metadata.json").readText())
        val dec = meta.getJSONObject("model_files").getJSONObject("decoder.onnx").getJSONObject("inputs")
        selfCaches = dec.keys().asSequence().filter { it.contains("cache_self") }.map { name ->
            val spec = dec.getJSONObject(name)
            val shape = spec.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
            Cache(name, shape, spec.getJSONObject("quantization_parameters").getInt("zero_point"))
        }.toList()

        fun options() = OrtSession.SessionOptions().apply {
            addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "htp_graph_finalization_optimization_mode" to "3",
                )
            )
            // An EPContext graph must not fall back to the CPU node by node.
            addConfigEntry("session.disable_cpu_ep_fallback", "0")
        }
        encoder = env.createSession(File(modelDir, "encoder_f.onnx").absolutePath, options())
        decoder = env.createSession(File(modelDir, "decoder_f.onnx").absolutePath, options())
        backend = "QNN HTP"
        loadMs = System.currentTimeMillis() - started
        Log.i(TAG, "loaded ${modelDir.name} in ${loadMs}ms; ${selfCaches.size} self caches")
    }

    /**
     * Transcribe up to thirty seconds of 16 kHz mono audio.
     *
     * @param language a Whisper code ("en", "hi", "ta", ...) or null to detect it.
     * @param translate English out whatever went in. Whisper Small was trained
     *   for this; Large-v3 Turbo was not, so the pipeline only asks it of Small.
     */
    fun transcribe(audio: FloatArray, language: String? = null, translate: Boolean = false): Chunk {
        load()
        val enc = encoder!!; val dec = decoder!!

        val t0 = System.currentTimeMillis()
        val features = mel.compute(audio)
        val t1 = System.currentTimeMillis()

        val featTensor = OnnxTensor.createTensor(
            env, java.nio.FloatBuffer.wrap(features), longArrayOf(1, mel.nMels.toLong(), MelSpectrogram.N_FRAMES.toLong()),
        )
        val encOut = enc.run(mapOf("input_features_f32" to featTensor))
        featTensor.close()
        val t2 = System.currentTimeMillis()

        // Cross caches: encoder outputs straight into decoder inputs, same names,
        // same quantisation -- no copy through Kotlin.
        val cross = HashMap<String, OnnxTensor>()
        for ((name, value) in encOut) cross[name] = value as OnnxTensor

        val selfIn = HashMap<String, OnnxTensor>()
        for (c in selfCaches) {
            val bytes = c.shape.fold(1L) { a, b -> a * b }.toInt()
            val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            repeat(bytes) { buf.put(c.zeroPoint.toByte()) }   // a real zero, in uint8
            buf.rewind()
            selfIn[c.name] = OnnxTensor.createTensor(env, buf, c.shape, OnnxJavaType.UINT8)
        }

        val mask = FloatArray(DECODE_LEN) { MASK_NEG }
        val prompt = ArrayList<Int>().apply {
            add(cfg.sot)
            if (language != null) add(cfg.languages[language] ?: error("unknown language $language"))
        }
        var detected: String? = language
        val output = ArrayList<Int>(prompt)
        val maxSteps = DECODE_LEN - 1
        var prevResult: OrtSession.Result? = null
        var n = 0
        try {
            while (n < maxSteps) {
                val id = output[n]
                mask[DECODE_LEN - n - 1] = 0f
                val inputs = HashMap<String, OnnxTensor>()
                inputs["input_ids"] = OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(intArrayOf(id)), longArrayOf(1, 1))
                inputs["attention_mask_f32"] = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(mask.copyOf()), longArrayOf(1, 1, 1, DECODE_LEN.toLong()))
                inputs["position_ids"] = OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(intArrayOf(n)), longArrayOf(1))
                inputs.putAll(selfIn)
                inputs.putAll(cross)

                val result = dec.run(inputs)
                inputs["input_ids"]!!.close(); inputs["attention_mask_f32"]!!.close(); inputs["position_ids"]!!.close()

                // Next step's self caches are this step's outputs.
                if (prevResult == null) selfIn.values.forEach { it.close() }
                prevResult?.close()
                prevResult = result
                for (c in selfCaches) {
                    selfIn[c.name] = result.get(c.name.replace("_in", "_out")).get() as OnnxTensor
                }

                val logits = (result.get("logits_f32").get() as OnnxTensor).floatBuffer

                if (n == 0 && language == null) {
                    // Language identification: the most likely language token.
                    var best = -1; var bestScore = -Float.MAX_VALUE
                    for ((code, lid) in cfg.languages) {
                        val s = logits.get(lid)
                        if (s > bestScore) { bestScore = s; best = lid; detected = code }
                    }
                    output.add(best)
                } else if (n >= output.size - 1) {
                    if (output.size == prompt.size + (if (language == null) 1 else 0)) {
                        // Force the task tokens once the language is in place.
                        output.add(if (translate) cfg.translate else cfg.transcribe); output.add(cfg.noTimestamps)
                    } else {
                        val next = pick(logits, output, prompt.size + 3)
                        output.add(next)
                        if (next == cfg.eot || isLooping(output)) break
                    }
                }
                n++
            }
        } finally {
            prevResult?.close()
            encOut.close()
        }
        val t3 = System.currentTimeMillis()
        val text = tokenizer.decode(output)
        Log.i(TAG, "chunk: lang=$detected tokens=${output.size} mel=${t1 - t0}ms enc=${t2 - t1}ms dec=${t3 - t2}ms: ${text.take(120)}")
        return Chunk(text, detected, output.size, t1 - t0, t2 - t1, t3 - t2)
    }

    /**
     * Greedy, with two blocks against the failure quantised Whisper falls into
     * on Hindi and Tamil: a token that would repeat a 4-gram already in this
     * chunk, and a token already repeated three times running. Measured before
     * this, a Hindi clip ended "आएएए दूएएएए" and a Tamil one "ச்ச்ச்ச".
     * Timestamp tokens are never chosen: the prompt asked for none.
     */
    private fun pick(buf: java.nio.FloatBuffer, out: List<Int>, textStart: Int): Int {
        val text = out.subList(minOf(textStart, out.size), out.size)
        val banned = IntArray(8); var nb = 0
        val n = 4
        if (text.size >= n - 1) {
            val t1 = text[text.size - 3]; val t2 = text[text.size - 2]; val t3 = text[text.size - 1]
            for (i in 0..text.size - n) {
                if (text[i] == t1 && text[i + 1] == t2 && text[i + 2] == t3 && nb < banned.size) banned[nb++] = text[i + 3]
            }
        }
        if (text.size >= 3 && text[text.size - 1] == text[text.size - 2] &&
            text[text.size - 2] == text[text.size - 3] && nb < banned.size) banned[nb++] = text.last()
        fun isBanned(id: Int): Boolean { for (j in 0 until nb) if (banned[j] == id) return true; return false }
        // Text tokens and end-of-text only: no timestamps, no other controls.
        var best = cfg.eot; var bestV = buf.get(cfg.eot)
        for (i in 0 until cfg.eot) { val v = buf.get(i); if (v > bestV) { bestV = v; best = i } }
        if (!isBanned(best)) return best
        best = cfg.eot; bestV = buf.get(cfg.eot)
        for (i in 0 until cfg.eot) {
            val v = buf.get(i); if (v > bestV && !isBanned(i)) { bestV = v; best = i }
        }
        return best
    }

    /**
     * Greedy Whisper on a quiet stretch can repeat one phrase until the window
     * fills. Stop when the last twelve tokens are the twelve before them.
     */
    private fun isLooping(ids: List<Int>): Boolean {
        val k = 12
        if (ids.size < 2 * k + 4) return false
        val a = ids.subList(ids.size - k, ids.size)
        val b = ids.subList(ids.size - 2 * k, ids.size - k)
        return a == b
    }

    override fun close() {
        runCatching { encoder?.close() }; runCatching { decoder?.close() }
        encoder = null; decoder = null
    }
}
