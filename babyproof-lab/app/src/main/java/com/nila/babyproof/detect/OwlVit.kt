package com.nila.babyproof.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.nila.babyproof.pipeline.Box
import com.nila.babyproof.pipeline.Candidate
import com.nila.babyproof.pipeline.Hazard
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * OWL-ViT, the open-vocabulary detector: the image half of Qualcomm AI Hub's
 * export (v0.64.0, ONNX, float), cut out by tools/split_owl.py. One 768x768
 * image in; for each of 576 patches a box, a unit class embedding, a shift and a
 * scale out. It runs on the Hexagon NPU through ONNX Runtime's QNN execution
 * provider, in fp16.
 *
 * The text half ran on the Mac: every hazard carries its query embedding, and a
 * patch's score for it is sigmoid((patch . query + shift) * scale) -- exactly what
 * the fused export computes. So a tile is one NPU run, not one per hazard: the
 * fused model's 160 runs a photo overheated the phone within minutes.
 *
 * Why the float export and not w8a16: the quantised graph has uint16 inputs and
 * outputs, which ONNX Runtime's Java API cannot create (the same reason Whisper
 * needed patched wrappers in the doctor-visit lab).
 *
 * The first load compiles the graph for the HTP, which takes a while; the
 * compiled context is cached next to the model ([CACHE]) and later loads read it.
 */
class OwlVit(private val modelDir: File, private val cacheDir: File) : Closeable {

    companion object {
        private const val TAG = "OwlVit"
        const val SIZE = 768
        const val MODEL = "owl_image.onnx"
        private const val DATA = "owl_image.data"
        private const val CACHE = "owl_image_ctx.onnx"
        private const val DIM = 512
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        /** The background a non-square tile is padded with: the dataset mean, so it reads as nothing. */
        private val PAD = Color.rgb(124, 116, 104)
    }

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    var backend = "-"; private set
    var loadMs = 0L; private set

    fun isInstalled() = File(modelDir, MODEL).isFile && File(modelDir, DATA).isFile

    fun load(allowCpu: Boolean = true) {
        if (session != null) return
        val started = System.currentTimeMillis()
        val cache = File(cacheDir, CACHE)
        fun qnn(generate: Boolean) = OrtSession.SessionOptions().apply {
            addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1",
                    "htp_graph_finalization_optimization_mode" to "3",
                )
            )
            if (generate) {
                addConfigEntry("ep.context_enable", "1")
                addConfigEntry("ep.context_file_path", cache.absolutePath)
                addConfigEntry("ep.context_embed_mode", "0")
            }
        }
        session = runCatching {
            if (cache.isFile) {
                env.createSession(cache.absolutePath, qnn(false)).also { backend = "QNN HTP (cached)" }
            } else {
                cacheDir.mkdirs()
                env.createSession(File(modelDir, MODEL).absolutePath, qnn(true)).also { backend = "QNN HTP" }
            }
        }.onFailure { Log.w(TAG, "QNN session failed: ${it.message}") }.getOrNull()
            ?: if (allowCpu) {
                env.createSession(File(modelDir, MODEL).absolutePath, OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(6)
                }).also { backend = "CPU" }
            } else throw IllegalStateException("OWL-ViT would not load on the NPU")
        loadMs = System.currentTimeMillis() - started
        Log.i(TAG, "loaded on $backend in ${loadMs}ms")
    }

    /**
     * Run every hazard query over one tile of the photo.
     *
     * @param photo the whole photo
     * @param tile which part of it, in 0..1 photo coordinates
     * @param perHazard most hits kept per query per tile
     */
    fun detect(photo: Bitmap, tile: Tiles.Tile, hazards: List<Hazard>, perHazard: Int = 4): List<Candidate> {
        val s = session ?: error("load() first")
        val (pixels, side, tw, th) = prepare(photo, tile.area)
        // OWL-ViT's box head looks only at the image, so patch i has the same box
        // for every query; only the scores differ. Collect all scores first, then
        // let each patch belong to the one hazard it matches best (relative to that
        // hazard's own threshold) -- otherwise a window is also a "socket", a
        // "lamp" and a "TV" at once.
        val bx: Array<FloatArray>
        val scores = Array(hazards.size) { FloatArray(0) }
        OnnxTensor.createTensor(env, pixels, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { image ->
            s.run(mapOf("pixel_values" to image)).use { r ->
                @Suppress("UNCHECKED_CAST")
                bx = (r.get("boxes").get().value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val emb = (r.get("div_2").get().value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val shift = (r.get("linear_147").get().value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val scale = (r.get("add_77").get().value as Array<Array<FloatArray>>)[0]
                hazards.forEachIndexed { q, h ->
                    require(h.embed.size == DIM) { "hazards.json has no query embedding for ${h.id}" }
                    scores[q] = FloatArray(bx.size) { i ->
                        var dot = 0f
                        val p = emb[i]
                        for (d in 0 until DIM) dot += p[d] * h.embed[d]
                        val logit = (dot + shift[i][0]) * scale[i][0]
                        1f / (1f + kotlin.math.exp(-logit))
                    }
                }
            }
        }
        val k = side.toFloat() / SIZE
        val perQuery = hazards.indices.associateWith { mutableListOf<Candidate>() }
        for (i in bx.indices) {
            var best = -1; var bestRatio = 1f
            for (q in hazards.indices) {
                val ratio = scores[q][i] / hazards[q].threshold
                if (ratio >= bestRatio) { bestRatio = ratio; best = q }
            }
            if (best < 0) continue
            val b = bx[i]
            // Model space (768 square, padded) -> tile 0..1 -> photo 0..1.
            val inTile = Box(b[0] * k / tw, b[1] * k / th, b[2] * k / tw, b[3] * k / th).clamp()
            perQuery[best]!! += Candidate(hazards[best], Tiles.toPhoto(tile.area, inTile), scores[best][i], tile.name)
        }
        val out = perQuery.values.flatMap { list -> list.sortedByDescending { it.score }.take(perHazard) }
        return out
    }

    private data class Prepared(val pixels: FloatBuffer, val side: Int, val tileW: Int, val tileH: Int)

    /** Crop the tile, pad it to a square at the top-left, scale to 768 and normalise to CLIP's statistics. */
    private fun prepare(photo: Bitmap, area: Box): Prepared {
        val x0 = (area.l * photo.width).toInt(); val y0 = (area.t * photo.height).toInt()
        val tw = ((area.r * photo.width).toInt() - x0).coerceAtLeast(1)
        val th = ((area.b * photo.height).toInt() - y0).coerceAtLeast(1)
        val side = maxOf(tw, th)
        val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(PAD)
            val scale = SIZE.toFloat() / side
            drawBitmap(photo, Rect(x0, y0, x0 + tw, y0 + th),
                Rect(0, 0, (tw * scale).toInt(), (th * scale).toInt()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(SIZE * SIZE)
        square.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        square.recycle()
        val plane = SIZE * SIZE
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            buf.put(i, ((c shr 16 and 0xFF) / 255f - MEAN[0]) / STD[0])
            buf.put(plane + i, ((c shr 8 and 0xFF) / 255f - MEAN[1]) / STD[1])
            buf.put(2 * plane + i, ((c and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        return Prepared(buf, side, tw, th)
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
    }
}
