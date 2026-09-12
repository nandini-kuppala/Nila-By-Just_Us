package com.nila.ddi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * What happens when two drugs are taken together, from DrugBank.
 *
 * This is an addition to the medicine check, not a replacement for any part of
 * it. The corpus in `knowledge.json` carries a hand-written `interacts_with`
 * list per medicine, which is precise, sourced and covers thirty-three drugs.
 * This covers **1,710**, which is the entire DrugBank DDI vocabulary, and it is
 * consulted only after the curated check has already run and produced its
 * answer. Every failure path here returns null and the review is exactly what
 * it was before.
 *
 * ## Two different questions, answered by two different things
 *
 * **Do these two interact at all?** Answered by DrugBank's own interaction
 * graph, shipped as a bitset in `ddi_graph.bin`. This is a fact, not a
 * prediction.
 *
 * **What kind of interaction is it?** Answered by the model, which is an
 * 86-way classifier over the DrugBank interaction ontology.
 *
 * Keeping those apart is the whole design, and it is not a stylistic
 * preference. The classifier has no "these two do not interact" class, so it
 * returns one of 86 interaction types for *any* pair put in front of it,
 * including two drugs with no relationship whatsoever. Measured on this
 * checkpoint, the softmax peak separates real DrugBank edges from non-edges at
 * AUC 0.78 -- at a threshold keeping 90% of true interactions, 54% of
 * non-interacting pairs get through it too. A confidence gate is therefore not
 * a usable substitute for the graph, and the model is never asked whether an
 * interaction exists.
 *
 * ## When the type is named, and when it is not
 *
 * Accuracy depends almost entirely on whether the model was trained on both
 * drugs, and barely at all on how confident it sounds:
 *
 * | both drugs trained on | one novel | both novel |
 * |---|---|---|
 * | 96.6% | 69.7% | 43.2% |
 *
 * Raising the confidence bar to 0.9 moves the middle column to 76% -- it does
 * not rescue it. So the gate is the training status of the two drugs, which is
 * known exactly, rather than a threshold on the model's own opinion of itself.
 * For a pair involving a drug the model never saw, the recorded interaction is
 * still reported and the type is left unnamed.
 *
 * Everything runs on this phone. No part of this makes a network call.
 */
class DrugInteractions private constructor(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "DrugInteractions"

        private const val MODEL = "ddi/ddi_model.onnx"
        private const val DRUGS = "ddi/ddi_drugs.bin"
        private const val GRAPH = "ddi/ddi_graph.bin"
        private const val VOCAB = "ddi/ddi_vocab.json"
        private const val TYPES = "ddi/ddi_types.json"

        private const val N_DRUGS = 1710
        private const val FP_HALF = 1024
        private const val N_BLOCKS = 64
        private const val BLOCK_SIZE = FP_HALF / N_BLOCKS
        private const val N_CLASSES = 86

        @Volatile private var instance: DrugInteractions? = null

        /**
         * One engine per process, for the same reason [com.nila.ocr.PaddleOcr]
         * has one: the weights are read-only and an ORT session per caller is
         * twenty megabytes each.
         */
        fun shared(context: Context): DrugInteractions =
            instance ?: synchronized(this) {
                instance ?: DrugInteractions(context.applicationContext)
                    .also { instance = it }
            }
    }

    /**
     * One answer about one ordered pair.
     *
     * [recorded] is DrugBank's; [description] is the model's, and is null when
     * the model is not in a position to name the type. A caller that prints
     * [description] without checking it is null gets nothing rather than a
     * guess, which is the intended shape.
     */
    data class Interaction(
        val subject: String,
        val other: String,
        val recorded: Boolean,
        val description: String?,
        val confidence: Float,
        /** False when either drug is outside what the model was trained on. */
        val reliable: Boolean,
    )

    // ------------------------------------------------------------- the tables

    private class Tables(
        val idf: FloatArray,
        /** Sparse Morgan counts: index and value arrays per drug. */
        val index: Array<ShortArray>,
        val value: Array<ByteArray>,
        val trained: BooleanArray,
        val edges: ByteArray,
        val names: List<String>,
        val types: List<String>,
    ) {
        fun counts(drug: Int, into: FloatArray, offset: Int) {
            java.util.Arrays.fill(into, offset, offset + FP_HALF, 0f)
            val idx = index[drug]
            val value = value[drug]
            for (i in idx.indices) {
                into[offset + (idx[i].toInt() and 0xFFFF)] =
                    (value[i].toInt() and 0xFF).toFloat()
            }
        }

        fun hasEdge(a: Int, b: Int): Boolean {
            val k = a.toLong() * N_DRUGS + b
            val byte = edges[(k ushr 3).toInt()].toInt() and 0xFF
            return (byte shr (k and 7L).toInt()) and 1 == 1
        }
    }

    @Volatile private var tables: Tables? = null
    @Volatile private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var byName: Map<String, Int> = emptyMap()
    private var failed = false

    /** Every drug name the graph knows, for a caller that wants to resolve one. */
    val vocabularySize: Int get() = loadTables()?.names?.size ?: 0

    @Synchronized
    private fun loadTables(): Tables? {
        tables?.let { return it }
        if (failed) return null
        return try {
            val built = readTables()
            byName = built.names.withIndex()
                .associate { (i, n) -> normalise(n) to i }
            tables = built
            built
        } catch (t: Throwable) {
            // A missing or truncated asset must cost the medicine check
            // nothing. It ran without any of this for its whole life.
            Log.w(TAG, "drug interaction tables unavailable; addon disabled", t)
            failed = true
            null
        }
    }

    private fun readTables(): Tables {
        val idf = FloatArray(FP_HALF)
        val index = arrayOfNulls<ShortArray>(N_DRUGS)
        val value = arrayOfNulls<ByteArray>(N_DRUGS)
        val trained = BooleanArray(N_DRUGS)

        context.assets.open(DRUGS).let(::DataInputStream).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(String(magic) == "NDDI") { "bad drug table header" }
            val head = ByteArray(12).also(input::readFully)
                .let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
            val version = head.int
            val drugs = head.int
            val width = head.int
            require(version == 2) { "unsupported drug table version $version" }
            require(drugs == N_DRUGS && width == FP_HALF) {
                "drug table is ${drugs}x$width, expected ${N_DRUGS}x$FP_HALF"
            }

            ByteArray(FP_HALF * 4).also(input::readFully)
                .let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
                .asFloatBuffer().get(idf)

            for (drug in 0 until N_DRUGS) {
                trained[drug] = input.readUnsignedByte() == 1
                val nnz = ByteArray(2).also(input::readFully)
                    .let { ((it[1].toInt() and 0xFF) shl 8) or (it[0].toInt() and 0xFF) }
                val raw = ByteArray(nnz * 2).also(input::readFully)
                val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                index[drug] = ShortArray(nnz).also { buffer.asShortBuffer().get(it) }
                value[drug] = ByteArray(nnz).also(input::readFully)
            }
        }

        val edges: ByteArray
        context.assets.open(GRAPH).let(::DataInputStream).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(String(magic) == "NDDG") { "bad graph header" }
            ByteArray(8).also(input::readFully)
            edges = ByteArray((N_DRUGS * N_DRUGS + 7) / 8).also(input::readFully)
        }

        fun strings(asset: String, key: String): List<String> {
            val json = context.assets.open(asset).bufferedReader().use { it.readText() }
            val array = JSONObject(json).getJSONArray(key)
            return (0 until array.length()).map { array.getString(it) }
        }

        val names = strings(VOCAB, "names")
        val types = strings(TYPES, "types")
        require(names.size == N_DRUGS && types.size == N_CLASSES) { "vocabulary shape" }

        return Tables(
            idf = idf,
            index = index.requireNoNulls(),
            value = value.requireNoNulls(),
            trained = trained,
            edges = edges,
            names = names,
            types = types,
        )
    }

    @Synchronized
    private fun loadSession(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return try {
            // Unpacked to a file and opened by path, the way
            // [com.nila.ocr.PaddleOcr] does. `createSession(ByteArray)` would
            // mean a 19.7 MB array on the Java heap *and* the runtime's own
            // native copy, in a process that at this moment is also holding
            // three OCR sessions and a decoded photograph.
            val file = materialise(MODEL)
            val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }
            val options = OrtSession.SessionOptions().apply {
                // One thread. This runs once per medicine check, on a phone
                // that may also be listening to a room, and a four-thread
                // burst is a worse trade than fifteen extra milliseconds.
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(file.absolutePath, options).also {
                session = it
                Log.i(TAG, "DDI model loaded (${file.length() / 1_000_000} MB)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DDI model would not load; interaction types unavailable", t)
            failed = true
            null
        }
    }

    private fun materialise(asset: String): java.io.File {
        val out = java.io.File(context.filesDir, asset.substringAfterLast('/'))
        if (out.exists() && out.length() > 0) return out
        context.assets.open(asset).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Load the weights now, because a check is about to happen. */
    fun prepare() { runCatching { loadTables(); loadSession() } }

    // -------------------------------------------------------------- resolving

    private fun normalise(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "")

    /**
     * A drug name as DrugBank spells it, or null.
     *
     * Deliberately exact after normalisation. Fuzzy matching belongs upstream
     * in [com.nila.assistant.KnowledgeIndex], where a wrong match is visible in
     * the identify step; a silent fuzzy match down here would attach a real
     * interaction to a drug nobody named.
     */
    fun resolve(name: String): Int? {
        loadTables() ?: return null
        val key = normalise(name)
        if (key.length < 4) return null
        byName[key]?.let { return it }
        // "Ibuprofen 400mg" and "Tab. Metformin" are what people actually write
        // in a medicines list. Try each word of four characters or more.
        return name.split(Regex("[^A-Za-z]+"))
            .asSequence()
            .filter { it.length >= 4 }
            .mapNotNull { byName[normalise(it)] }
            .firstOrNull()
    }

    fun nameOf(drug: Int): String? = loadTables()?.names?.getOrNull(drug)

    // ------------------------------------------------------------ the answer

    /**
     * What DrugBank records between these two, and what kind it is.
     *
     * Returns null -- meaning "say nothing" -- when the graph has no edge in
     * either direction, when an asset is missing, or when anything at all goes
     * wrong. There is no error path that reaches the screen.
     */
    fun between(drug1: Int, drug2: Int): Interaction? = try {
        val tables = loadTables()
        when {
            tables == null -> null
            drug1 == drug2 -> null
            drug1 !in 0 until N_DRUGS || drug2 !in 0 until N_DRUGS -> null
            else -> {
                // DrugBank records each interaction in one direction, and the
                // direction is the sentence's subject. Whichever way round it
                // was recorded is the way round the model is asked, so the
                // "#Drug1 increases the activity of #Drug2" template lands on
                // the right drug.
                val forward = tables.hasEdge(drug1, drug2)
                val backward = tables.hasEdge(drug2, drug1)
                when {
                    forward -> describe(tables, drug1, drug2)
                    backward -> describe(tables, drug2, drug1)
                    else -> null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "interaction lookup failed", t)
        null
    }

    private fun describe(tables: Tables, subject: Int, other: Int): Interaction {
        val reliable = tables.trained[subject] && tables.trained[other]
        var description: String? = null
        var confidence = 0f
        if (reliable) {
            val logits = runCatching { infer(tables, subject, other) }
                .onFailure { Log.w(TAG, "DDI inference failed", it) }
                .getOrNull()
            if (logits != null) {
                var best = 0
                for (i in logits.indices) if (logits[i] > logits[best]) best = i
                var sum = 0.0
                for (v in logits) sum += Math.exp((v - logits[best]).toDouble())
                confidence = (1.0 / sum).toFloat()
                description = tables.types[best]
                    .replace("#Drug1", tables.names[subject])
                    .replace("#Drug2", tables.names[other])
            }
        }
        return Interaction(
            subject = tables.names[subject],
            other = tables.names[other],
            recorded = true,
            description = description,
            confidence = confidence,
            reliable = reliable,
        )
    }

    /**
     * The feature transform, reproduced exactly.
     *
     * These four views of the same pair are what the network was trained on:
     * the raw Morgan counts, the same counts weighted by the inverse document
     * frequency fitted on the training fold, a 64-block popcount sketch per
     * drug, and four scalar density statistics. They are derived here rather
     * than shipped because they are derived there -- one array of 2,048 counts
     * per pair is the only thing that has to be looked up.
     */
    private fun infer(tables: Tables, drug1: Int, drug2: Int): FloatArray {
        val session = loadSession() ?: throw IllegalStateException("no session")
        val env = environment ?: throw IllegalStateException("no environment")

        val raw = FloatArray(2 * FP_HALF)
        tables.counts(drug1, raw, 0)
        tables.counts(drug2, raw, FP_HALF)

        val weighted = FloatArray(2 * FP_HALF)
        for (i in 0 until FP_HALF) {
            weighted[i] = raw[i] * tables.idf[i]
            weighted[FP_HALF + i] = raw[FP_HALF + i] * tables.idf[i]
        }

        val blocks = FloatArray(2 * N_BLOCKS)
        for (half in 0..1) {
            for (block in 0 until N_BLOCKS) {
                var sum = 0f
                val from = half * FP_HALF + block * BLOCK_SIZE
                for (i in from until from + BLOCK_SIZE) sum += raw[i]
                blocks[half * N_BLOCKS + block] = sum / BLOCK_SIZE
            }
        }

        var p1 = 0f
        var p2 = 0f
        for (i in 0 until FP_HALF) { p1 += raw[i]; p2 += raw[FP_HALF + i] }
        val stats = floatArrayOf(
            p1 / FP_HALF,
            p2 / FP_HALF,
            Math.abs(p1 - p2) / FP_HALF,
            minOf(p1, p2) / maxOf(maxOf(p1, p2), 1f),
        )

        fun tensor(data: FloatArray, width: Int) = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data), longArrayOf(1, width.toLong())
        )
        val id1 = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(drug1.toLong())), longArrayOf(1)
        )
        val id2 = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(drug2.toLong())), longArrayOf(1)
        )
        val inputs = linkedMapOf(
            "x_raw" to tensor(raw, 2 * FP_HALF),
            "x_weighted" to tensor(weighted, 2 * FP_HALF),
            "blocks" to tensor(blocks, 2 * N_BLOCKS),
            "stats" to tensor(stats, 4),
            "drug1_id" to id1,
            "drug2_id" to id2,
        )
        try {
            session.run(inputs).use { output ->
                @Suppress("UNCHECKED_CAST")
                return (output[0].value as Array<FloatArray>)[0]
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }
}
