package com.nila.babyproof.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.nila.babyproof.detect.OwlVit
import com.nila.babyproof.detect.Tiles
import com.nila.babyproof.vlm.NpuVlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Photo in, babyproofing list out:
 *
 *   1 Find     OWL-ViT over the whole photo and four overlapping quarters, one run per hazard word (NPU)
 *   2 Confirm  Intern3.5-VL-2B on a crop around each strong hit, four-line form (NPU)
 *   3 Room     Intern3.5-VL-2B once on the whole photo, for what the word list has no word for
 *   4 Decide   [Rules]: confirm, drop, "check this" or "already done"; merge duplicates
 *
 * The two models take turns on the NPU: the detector finishes before the VLM loads.
 */
class BabyproofPipeline(private val context: Context) {

    enum class Mode { DETECTOR, FULL }

    companion object {
        private const val TAG = "Babyproof"
        /** Most crops shown to the vision model per photo. */
        const val MAX_LOOKS = 12
        /** Photos are scaled down to this on the long side before anything runs. */
        const val MAX_SIDE = 2048
    }

    val hazards: List<Hazard> by lazy {
        Hazard.parseAll(context.assets.open("babyproof/hazards.json").bufferedReader().readText())
    }
    private val catalogue by lazy { hazards.associateBy { it.id } }

    val modelsDir: File get() = context.getExternalFilesDir("models")!!

    init {
        // Made by the app, never by adb: on Android 16 a folder adb creates under
        // Android/data cannot be read by the app it belongs to.
        listOf("owl_vit", "intern-vl").forEach { File(modelsDir, it).mkdirs() }
        listOf("eval", "demo").forEach { File(context.getExternalFilesDir(null), "$it/images").mkdirs() }
    }
    val detector by lazy { OwlVit(File(modelsDir, "owl_vit"), File(context.filesDir, "qnn_cache")) }

    /** Recreated after every close: closing also shuts its worker thread. */
    private var vlmInstance: NpuVlm? = null
    val vlm: NpuVlm get() = vlmInstance ?: NpuVlm(context).also { vlmInstance = it }

    /** Free the NPU of the vision model (before the detector loads again). */
    fun closeVlm() { vlmInstance?.close(); vlmInstance = null }

    data class Found(val candidates: List<Candidate>, val timings: Map<String, Long>, val notes: List<String>)

    /** Step 1 alone: the detector over the whole photo and four quarters, then NMS. */
    suspend fun find(photo: Bitmap, onStep: (String) -> Unit = {}): Found = withContext(Dispatchers.Default) {
        if (vlmInstance?.isReady == true) closeVlm()
        onStep("Loading the detector")
        detector.load()
        val tiles = Tiles.plan(photo.width, photo.height)
        val raw = mutableListOf<Candidate>()
        val t = System.currentTimeMillis()
        tiles.forEachIndexed { i, tile ->
            onStep("Looking for hazards: part ${i + 1} of ${tiles.size}")
            raw += detector.detect(photo, tile, hazards)
        }
        val ms = System.currentTimeMillis() - t
        val candidates = Tiles.nms(raw)
        Log.i(TAG, "detector: ${raw.size} raw hits, ${candidates.size} after NMS in ${ms}ms on ${detector.backend}")
        Found(candidates, mapOf("detector_load" to detector.loadMs, "detect" to ms), listOf("detector on ${detector.backend}"))
    }

    /** Steps 2-4: the vision model checks the hits and the room, then the rules decide. */
    suspend fun confirm(photo: Bitmap, found: Found, onStep: (String) -> Unit = {}): Scan = withContext(Dispatchers.Default) {
        val timings = LinkedHashMap(found.timings)
        val notes = found.notes.toMutableList()
        val candidates = found.candidates
        fun detectorOnly(why: String): Scan {
            notes += why
            return Scan(Rules.merge(candidates.mapNotNull { Rules.decide(it, null) }), candidates, emptyList(), timings, notes)
        }
        if (!vlm.isInstalled()) return@withContext detectorOnly("vision model not installed: detector only")

        // The detector is done with the NPU before the VLM asks for it.
        detector.close()
        onStep("Loading the vision model")
        val tLoad = System.currentTimeMillis()
        val ready = vlm.start { s -> if (s is NpuVlm.State.Loading) onStep("Loading the vision model (try ${s.attempt})") }
        if (!vlm.isReady || !ready) return@withContext detectorOnly("vision model did not load: detector only")
        timings["vlm_load"] = System.currentTimeMillis() - tLoad

        val toCheck = pickForLooks(candidates)
        val findings = mutableListOf<Finding>()
        val looks = mutableListOf<Look>()
        val tLook = System.currentTimeMillis()
        toCheck.forEachIndexed { i, c ->
            onStep("Checking ${i + 1} of ${toCheck.size}: ${c.hazard.label.lowercase()}")
            val crop = crop(photo, c.box)
            val raw = vlm.look(crop, Prompts.SYSTEM, Prompts.crop(c.hazard))
            looks += Look(c, raw)
            val answer = raw?.let { Prompts.parseCrop(it) }
            Log.i(TAG, "look ${c.hazard.id} ${"%.3f".format(c.score)} -> ${raw?.replace('\n', ' ')}")
            Rules.decide(c, answer, catalogue)?.let { findings += it }
        }
        timings["looks"] = System.currentTimeMillis() - tLook
        timings["looks_n"] = toCheck.size.toLong()

        onStep("Looking at the whole room")
        val tScene = System.currentTimeMillis()
        val sceneRaw = vlm.look(fit(photo, 1024), Prompts.SYSTEM, Prompts.SCENE)
        val scene = sceneRaw?.let { Prompts.parseScene(it) }.orEmpty()
        timings["scene"] = System.currentTimeMillis() - tScene
        Log.i(TAG, "scene -> ${sceneRaw?.replace('\n', ' ')}")

        Scan(Rules.addScene(Rules.merge(findings), scene, catalogue), candidates, scene, timings, notes, looks, sceneRaw)
    }

    /** The whole thing for one photo, as the app runs it. */
    suspend fun scan(photo: Bitmap, mode: Mode, onStep: (String) -> Unit = {}): Scan {
        val t0 = System.currentTimeMillis()
        val found = find(photo, onStep)
        val scan = if (mode == Mode.DETECTOR) {
            Scan(Rules.merge(found.candidates.mapNotNull { Rules.decide(it, null) }), found.candidates, emptyList(),
                found.timings, found.notes)
        } else confirm(photo, found, onStep)
        return scan.copy(timings = scan.timings + ("total" to System.currentTimeMillis() - t0))
    }

    /**
     * The strongest hits, at most [MAX_LOOKS], with every critical hazard's best
     * hit guaranteed a look before weaker non-critical ones.
     */
    private fun pickForLooks(candidates: List<Candidate>): List<Candidate> {
        val bestCritical = candidates.filter { it.hazard.critical }.groupBy { it.hazard.id }
            .map { (_, g) -> g.maxBy { it.strength } }
        val rest = candidates.filter { it !in bestCritical }.sortedByDescending { it.strength }
        return (bestCritical.sortedByDescending { it.strength } + rest).take(MAX_LOOKS)
    }

    /** A square crop around a box with room for context: at least 1.8x the box, at least a fifth of the photo. */
    fun crop(photo: Bitmap, box: Box): Bitmap {
        val w = photo.width; val h = photo.height
        val bw = box.w * w; val bh = box.h * h
        val side = maxOf(bw * 1.8f, bh * 1.8f, minOf(w, h) * 0.22f).coerceAtMost(minOf(w, h).toFloat()).toInt()
        val cx = (box.cx * w).toInt(); val cy = (box.cy * h).toInt()
        val x = (cx - side / 2).coerceIn(0, w - side)
        val y = (cy - side / 2).coerceIn(0, h - side)
        return fit(Bitmap.createBitmap(photo, x, y, side, side), 896)
    }

    private fun fit(b: Bitmap, max: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= max) return b
        val k = max.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    /** Decode a photo the right way up and no larger than [MAX_SIDE]. */
    fun decode(open: () -> InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("not an image")
        val rotation = runCatching { open().use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val upright = if (rotation != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true) else bmp
        return fit(upright, MAX_SIDE).copy(Bitmap.Config.ARGB_8888, false)
    }
}
