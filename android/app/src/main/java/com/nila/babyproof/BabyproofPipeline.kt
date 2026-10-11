package com.nila.babyproof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.nila.npu.NpuArbiter
import com.nila.vision.guard.RemoteVlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Photo in, babyproofing list out:
 *
 *   1 Find     OWL-ViT's image half over the whole photo and four overlapping
 *              quarters, five NPU runs; every hazard word is scored against each
 *              patch with its precomputed query embedding
 *   2 Confirm  Intern3.5-VL-2B on a crop around each strong hit, four-line form
 *   3 Room     Intern3.5-VL-2B once on the whole photo, for what the word list has no word for
 *   4 Decide   [Rules]: confirm, drop, "check this" or "already done"; merge duplicates
 *
 * Built and measured in babyproof-lab/ first (47 labelled photos), the way the
 * doctor visit was.
 *
 * The NPU is shared the way the doctor visit shares it: the chat model is moved
 * off before the detector loads, the detector is closed before the vision model
 * loads, and the vision model is the watch's own -- in its own process, through
 * [RemoteVlm] -- so there is never a second copy of it on the NPU. The screen does
 * not offer a scan while the watch is using that model.
 */
class BabyproofPipeline(private val context: Context) {

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

    private val modelsDir: File get() = context.getExternalFilesDir("models")!!

    init {
        // Made by the app, never by adb: on Android 16 a folder adb creates under
        // Android/data cannot be read by the app it belongs to.
        File(modelsDir, "owl_vit").mkdirs()
    }

    private val detector by lazy { OwlVit(File(modelsDir, "owl_vit"), File(context.filesDir, "qnn_cache")) }

    private fun remoteVlm() = RemoteVlm(context, log = { Log.i(TAG, it) }, onState = {}, onDied = {})

    fun detectorInstalled() = detector.isInstalled()
    fun visionModelInstalled() = remoteVlm().isInstalled()

    /**
     * The whole scan. [onStep] gets a short sentence for the progress card and a
     * 0..1 fraction of the way through.
     */
    suspend fun scan(photo: Bitmap, onStep: (String, Float) -> Unit = { _, _ -> }): Scan = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val timings = LinkedHashMap<String, Long>()
        val notes = mutableListOf<String>()
        val useVlm = visionModelInstalled()

        // ---- 1 find
        var candidates = emptyList<Candidate>()
        if (detector.isInstalled()) {
            onStep("Getting the phone ready", 0.02f)
            claimNpu()
            try {
                detector.load()
                timings["detector_load"] = detector.loadMs
                val tiles = Tiles.plan(photo.width, photo.height)
                val raw = mutableListOf<Candidate>()
                val t = System.currentTimeMillis()
                tiles.forEachIndexed { i, tile ->
                    onStep("Looking around the room", 0.05f + 0.15f * i / tiles.size)
                    raw += detector.detect(photo, tile, hazards)
                }
                timings["detect"] = System.currentTimeMillis() - t
                candidates = Tiles.nms(raw)
                notes += "detector on ${detector.backend}"
                Log.i(TAG, "detector: ${raw.size} raw hits, ${candidates.size} after NMS in ${timings["detect"]}ms on ${detector.backend}")
            } finally {
                // The vision model must never load beside it.
                detector.close()
                NpuArbiter.visitDone()
            }
        } else {
            notes += "detector not installed"
        }

        if (!useVlm) {
            notes += "vision model not installed: detector only"
            val findings = Rules.merge(candidates.mapNotNull { Rules.decide(it, null) })
            timings["total"] = System.currentTimeMillis() - t0
            return@withContext Scan(findings, candidates, emptyList(), timings, notes)
        }

        // ---- 2 and 3: the vision model checks the hits, then the room
        val vlm = remoteVlm()
        try {
            onStep("Waking the vision model", 0.22f)
            val tLoad = System.currentTimeMillis()
            var loaded = false
            for (attempt in 1..3) {
                loaded = vlm.load()
                if (loaded) break
                delay(1_500)
            }
            if (!loaded) {
                notes += "vision model did not load: detector only"
                val findings = Rules.merge(candidates.mapNotNull { Rules.decide(it, null) })
                timings["total"] = System.currentTimeMillis() - t0
                return@withContext Scan(findings, candidates, emptyList(), timings, notes)
            }
            timings["vlm_load"] = System.currentTimeMillis() - tLoad

            val toCheck = pickForLooks(candidates)
            val findings = mutableListOf<Finding>()
            val looks = mutableListOf<Look>()
            val tLook = System.currentTimeMillis()
            toCheck.forEachIndexed { i, c ->
                onStep("Checking the ${c.hazard.label.lowercase()}", 0.3f + 0.55f * i / toCheck.size.coerceAtLeast(1))
                val raw = vlm.look(crop(photo, c.box), Prompts.SYSTEM, Prompts.crop(c.hazard))
                looks += Look(c, raw)
                val answer = raw?.let { Prompts.parseCrop(it) }
                Log.i(TAG, "look ${c.hazard.id} ${"%.3f".format(c.score)} -> ${raw?.replace('\n', ' ')}")
                Rules.decide(c, answer, catalogue)?.let { findings += it }
            }
            timings["looks"] = System.currentTimeMillis() - tLook

            onStep("Looking at the whole room", 0.88f)
            val tScene = System.currentTimeMillis()
            val sceneRaw = vlm.look(fit(photo, 1024), Prompts.SYSTEM, Prompts.SCENE)
            val scene = sceneRaw?.let { Prompts.parseScene(it) }.orEmpty()
            timings["scene"] = System.currentTimeMillis() - tScene
            Log.i(TAG, "scene -> ${sceneRaw?.replace('\n', ' ')}")
            onStep("Writing your list", 0.97f)
            timings["total"] = System.currentTimeMillis() - t0
            Scan(Rules.addScene(Rules.merge(findings), scene, catalogue), candidates, scene, timings, notes, looks, sceneRaw)
        } finally {
            vlm.close()
        }
    }

    /** The chat model off the NPU first; it may be mid-reply, so ask a few times. */
    private suspend fun claimNpu() {
        repeat(10) {
            if (NpuArbiter.claimForVisit()) return
            delay(1_000)
        }
        Log.w(TAG, "the chat model kept the NPU busy; the detector will share it")
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
    private fun crop(photo: Bitmap, box: Box): Bitmap {
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
