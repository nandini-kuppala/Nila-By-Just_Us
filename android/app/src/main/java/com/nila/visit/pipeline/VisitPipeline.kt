package com.nila.visit.pipeline

import android.content.Context
import android.util.Log
import com.nila.visit.asr.MelSpectrogram
import com.nila.visit.asr.WhisperAsr
import com.nila.visit.asr.WhisperTokenizer
import com.nila.visit.audio.Audio
import com.nila.visit.extract.Extractor
import com.nila.visit.extract.VisitNotes
import com.nila.visit.llm.VisitLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Speech, then language, never both at once.
 *
 * The phone has one Hexagon NPU and two models that want it. Running them one
 * after the other is not a compromise: the notes cannot be written before the
 * doctor has finished speaking anyway. So Whisper holds the NPU while there is
 * audio to read, lets go completely -- sessions closed, context binaries
 * unmapped -- and only then is Qwen loaded onto it. The main app learned the
 * hard way (2.5.1) that two models resident on the NPU can take the process
 * down; this design never gets there.
 */
class VisitPipeline(private val context: Context) {

    companion object {
        private const val TAG = "VisitPipeline"
        /** Only Turbo ships in the app; Small was the lab's faster, rougher option. */
        val MODELS = mapOf("turbo" to "whisper-turbo")

        /** Scripts that cost Whisper several tokens a letter; see [transcribe]. */
        val LONG_TOKEN_LANGUAGES = setOf(
            "hi", "ta", "te", "kn", "ml", "bn", "mr", "gu", "pa", "ur", "or", "as", "ne", "si", "ar",
        )
    }

    data class Stage(val name: String, val ms: Long, val detail: String = "")

    data class Result(
        val transcript: String,
        val language: String?,
        val notes: VisitNotes,
        val stages: List<Stage>,
        val chunks: List<WhisperAsr.Chunk>,
        val llmRaw: String,
        val llm: VisitLlm.Generation?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("transcript", transcript)
            .put("language", language ?: JSONObject.NULL)
            .put("notes", notes.toJson())
            .put("stages", JSONArray(stages.map { JSONObject().put("name", it.name).put("ms", it.ms).put("detail", it.detail) }))
            .put("chunks", JSONArray(chunks.map {
                JSONObject().put("text", it.text).put("language", it.language ?: JSONObject.NULL)
                    .put("tokens", it.tokens).put("mel_ms", it.melMs).put("enc_ms", it.encoderMs).put("dec_ms", it.decoderMs)
            }))
            .put("llm_raw", llmRaw)
            .put("llm", llm?.let {
                JSONObject().put("prompt_tokens", it.promptTokens).put("generated_tokens", it.generatedTokens)
                    .put("prefill_tps", it.prefillTps).put("decode_tps", it.decodeTps)
                    .put("total_ms", it.totalMs).put("load_ms", it.loadMs)
            } ?: JSONObject.NULL)
    }

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "models")
    fun whisperDir(key: String): File = File(modelsDir(), MODELS[key] ?: error("model $key"))
    /**
     * The GGUF Ask downloads -- one copy of the weights serves both. Looked up
     * the way NpuLlm.modelFile does, without building an engine to ask it.
     */
    fun llmFile(): File {
        val name = com.nila.assistant.NpuLlm.GGUF_FILE
        return File(context.getExternalFilesDir("models"), name).takeIf { it.exists() }
            ?: File(File(context.filesDir, "local"), name)
    }

    fun whisperInstalled(key: String = "turbo"): Boolean = whisperDir(key).let { d ->
        listOf("metadata.json", "encoder_f.onnx", "decoder_f.onnx", "encoder_qairt_context.bin", "decoder_qairt_context.bin")
            .all { File(d, it).length() > 0 }
    }

    fun llmInstalled(): Boolean = llmFile().length() > 1_000_000_000

    /** Speech to text, on the NPU, then let go of it. */
    suspend fun transcribe(
        audio: FloatArray,
        model: String = "turbo",
        language: String? = null,
        translate: Boolean = false,
        onChunk: (Int, Int, WhisperAsr.Chunk) -> Unit = { _, _, _ -> },
    ): Triple<String, List<WhisperAsr.Chunk>, List<Stage>> = withContext(Dispatchers.Default) {
        val stages = ArrayList<Stage>()
        @Suppress("NAME_SHADOWING") val audio = Audio.normalise(audio)
        val dir = whisperDir(model)
        val tok = WhisperTokenizer.parse(
            context.assets.open("visit/whisper/$model/tokens.json").bufferedReader().readText(),
            context.assets.open("visit/whisper/$model/config.json").bufferedReader().readText(),
        )
        val filters = context.assets.open("visit/mel_${tok.config.mels}.bin").use { s ->
            val b = java.nio.ByteBuffer.wrap(s.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(b.remaining()).also { b.get(it) }
        }
        val asr = WhisperAsr(dir, tok, MelSpectrogram(filters, tok.config.mels))
        val chunks = ArrayList<WhisperAsr.Chunk>()
        try {
            val t0 = System.currentTimeMillis()
            asr.load()
            stages += Stage("whisper-load", System.currentTimeMillis() - t0, "$model on ${asr.backend}")
            var lang = language
            val t1 = System.currentTimeMillis()
            // The decoder has 200 token slots per piece, fixed by the compiled
            // model. Thirty seconds of English is about 100 tokens; the same
            // thirty seconds of Hindi, Tamil or Telugu is several hundred,
            // because each letter is several bytes and Whisper's tokens are
            // byte-level. A Hindi piece measured at exactly 200 tokens and fell
            // apart in its second half. So the language is settled first --
            // forced, or detected on the opening ten seconds -- and pieces for
            // the long-token scripts are cut to about ten seconds.
            if (lang == null) {
                lang = asr.transcribe(audio.copyOfRange(0, minOf(audio.size, 10 * Audio.RATE)), null).language
                stages += Stage("language-id", System.currentTimeMillis() - t1, "detected $lang")
            }
            val pieces = Audio.segmentByPauses(audio, maxSeconds = if (lang in LONG_TOKEN_LANGUAGES) 8 else 25)
            pieces.forEachIndexed { i, piece ->
                if (Audio.isSilent(piece)) return@forEachIndexed
                // The first chunk decides the language; later chunks keep it,
                // so a pause does not flip the transcript into English.
                val c = asr.transcribe(piece, lang, translate)
                if (lang == null) lang = c.language
                chunks += c
                onChunk(i, pieces.size, c)
            }
            stages += Stage("whisper-run", System.currentTimeMillis() - t1,
                "${pieces.size} pieces, ${audio.size / Audio.RATE}s audio, lang=$lang")
        } finally {
            val t = System.currentTimeMillis()
            asr.close()
            stages += Stage("whisper-release", System.currentTimeMillis() - t)
        }
        Triple(chunks.joinToString(" ") { it.text }.trim(), chunks, stages)
    }

    /** Text to notes, on the NPU, then let go of it. */
    suspend fun extract(
        transcript: String,
        visit: LocalDate,
        birth: LocalDate?,
        onToken: (String) -> Unit = {},
        onPass: (Extractor.Pass, VisitNotes) -> Unit = { _, _ -> },
    ): Triple<VisitNotes, String, Pair<VisitLlm.Generation?, List<Stage>>> {
        val stages = ArrayList<Stage>()
        val llm = VisitLlm(context, llmFile())
        try {
            val t0 = System.currentTimeMillis()
            llm.load()
            stages += Stage("llm-load", System.currentTimeMillis() - t0, "Qwen3.5-2B on the NPU")
            val replies = LinkedHashMap<Extractor.Pass, String>()
            var last: VisitLlm.Generation? = null
            var genMs = 0L; var tokensOut = 0L
            for (pass in Extractor.Pass.entries) {
                val max = when (pass) { Extractor.Pass.MEDICINES -> 600; Extractor.Pass.DATES -> 450; Extractor.Pass.ADVICE -> 700 }
                val gen = llm.generate(Extractor.prompt(pass, transcript, visit), maxTokens = max, onToken = onToken)
                replies[pass] = gen.text
                stages += Stage("llm-${pass.name.lowercase()}", gen.totalMs,
                    "${gen.promptTokens} in, ${gen.generatedTokens} out, ${"%.0f".format(gen.decodeTps)} tok/s")
                genMs += gen.totalMs; tokensOut += gen.generatedTokens; last = gen
                // What is known so far, for the screen to show as it arrives.
                onPass(pass, Extractor.parse(replies, transcript, visit, birth))
            }
            stages += Stage("llm-run", genMs, "$tokensOut tokens over ${replies.size} passes")
            val t1 = System.currentTimeMillis()
            val notes = Extractor.parse(replies, transcript, visit, birth)
            stages += Stage("parse+guard", System.currentTimeMillis() - t1, "${notes.dropped.size} dropped")
            val raw = replies.entries.joinToString("\n\n") { "## ${it.key}\n${it.value}" }
            val gen = last!!.copy(totalMs = genMs, generatedTokens = tokensOut)
            Log.i(TAG, "extracted: ${notes.medicines.size} medicines, ${notes.appointments.size} visits, dropped ${notes.dropped.size}")
            return Triple(notes, raw, gen to stages)
        } finally {
            val t = System.currentTimeMillis()
            llm.close()
            stages += Stage("llm-release", System.currentTimeMillis() - t)
        }
    }

    suspend fun run(
        audio: FloatArray?,
        transcriptIfNoAudio: String?,
        visit: LocalDate,
        birth: LocalDate?,
        model: String = "turbo",
        language: String? = null,
    ): Result {
        val stages = ArrayList<Stage>()
        var transcript = transcriptIfNoAudio ?: ""
        var chunks: List<WhisperAsr.Chunk> = emptyList()
        if (audio != null) {
            val (t, c, s) = transcribe(audio, model, language)
            transcript = t; chunks = c; stages += s
        }
        val (notes, raw, extra) = extract(transcript, visit, birth)
        stages += extra.second
        return Result(transcript, chunks.firstOrNull()?.language, notes, stages, chunks, raw, extra.first)
    }
}
