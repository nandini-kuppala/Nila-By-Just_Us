package com.nila.visit.asr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Whisper's vocabulary, for turning token ids back into text.
 *
 * Whisper uses GPT-2's byte-level BPE: every token is a run of *bytes*, written
 * with a printable stand-in character for each byte. Decoding maps the
 * characters back to bytes and only then reads UTF-8 -- which matters here,
 * because a Hindi or Tamil letter is three bytes and often split across tokens.
 * Joining token strings directly produces mojibake.
 */
class WhisperTokenizer(private val tokens: Array<String>, val config: Config) {

    data class Config(
        val vocab: Int,
        val mels: Int,
        val decoderLayers: Int,
        val heads: Int,
        val dModel: Int,
        val sot: Int,
        val eot: Int,
        val transcribe: Int,
        val translate: Int,
        val noTimestamps: Int,
        val timestampBegin: Int,
        /** "en" -> 50259, "hi" -> 50276, ... */
        val languages: Map<String, Int>,
    ) {
        val languageIds: Set<Int> get() = languages.values.toSet()
        fun languageOf(id: Int): String? = languages.entries.firstOrNull { it.value == id }?.key
    }

    companion object {
        fun parse(tokensJson: String, configJson: String): WhisperTokenizer {
            val arr = JSONArray(tokensJson)
            val tokens = Array(arr.length()) { arr.getString(it) }
            val c = JSONObject(configJson)
            val langs = c.getJSONObject("languages").let { o ->
                o.keys().asSequence().associateWith { o.getInt(it) }
            }
            return WhisperTokenizer(
                tokens,
                Config(
                    vocab = c.getInt("vocab"), mels = c.getInt("mels"),
                    decoderLayers = c.getInt("decoder_layers"), heads = c.getInt("heads"),
                    dModel = c.getInt("d_model"), sot = c.getInt("sot"), eot = c.getInt("eot"),
                    transcribe = c.getInt("transcribe"), translate = c.getInt("translate"),
                    noTimestamps = c.getInt("notimestamps"), timestampBegin = c.getInt("timestamp_begin"),
                    languages = langs,
                ),
            )
        }

        /** GPT-2's bytes_to_unicode, inverted: printable stand-in -> byte. */
        private val BYTE_OF: Map<Char, Int> by lazy {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }
    }

    /** Everything from end-of-text upwards is a control token, never text. */
    fun isSpecial(id: Int): Boolean = id >= config.eot

    fun decode(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id < 0 || id >= tokens.size || isSpecial(id)) continue
            for (ch in tokens[id]) {
                val b = BYTE_OF[ch] ?: continue
                bytes.write(b)
            }
        }
        return bytes.toString(Charsets.UTF_8.name()).trim()
    }
}
