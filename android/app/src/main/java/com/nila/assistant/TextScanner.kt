package com.nila.assistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nila.ocr.PaddleOcr
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads printed text off a photograph, on device.
 *
 * Three recognisers, tried in that order and pooled:
 *
 *  - **PP-OCRv4** ([PaddleOcr]) leads. It is the engine the original
 *    Nurture-Sync pipeline used server-side, and the reason it reads a torn or
 *    skewed blister sheet is its two extra stages: rotated-box detection and an
 *    explicit angle classifier. Neither has an ML Kit equivalent.
 *  - **ML Kit Latin** is a strong second on clean, upright, well-lit text --
 *    which is most photographs -- and it costs nothing to keep.
 *  - **ML Kit Devanagari**, because Indian medicine packaging routinely carries
 *    the brand in Latin and the manufacturer details in Devanagari, and the
 *    Latin model returns nothing useful for the latter.
 *
 * All three run locally and ship inside the app -- no image leaves the phone,
 * which matters more here than usual given what people photograph.
 */
class TextScanner(private val context: Context) {

    private companion object {
        const val TAG = "TextScanner"

        /**
         * How long one recogniser gets before the pass is abandoned.
         *
         * Generous: the slowest honest read in the test corpus -- a 12
         * megapixel shop shelf -- is a little over four seconds. This is not a
         * performance budget, it is the line between slow and never.
         */
        const val PASS_TIMEOUT_MS = 20_000L
    }

    // Shared: the ONNX sessions are thread-safe and hold 16 MB of weights.
    private val paddle = PaddleOcr.shared(context)

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    data class Scan(
        val text: String,
        val lines: List<String>,
        val blocks: Int,
        /**
         * True when a recogniser was still fetching its model.
         *
         * ML Kit downloads the OCR model on first use, and until it lands every
         * call returns empty. Without this flag that is indistinguishable from
         * "there was no text in the photo", and the app tells a user their
         * medicine is unrecognised when in fact it never looked.
         */
        val modelNotReady: Boolean = false,
        /** How many attempts it took. Useful for telling slow from broken. */
        val passes: Int = 1,
    )

    /**
     * Nudge ML Kit into fetching its models.
     *
     * Cheap, idempotent, and worth doing at startup so the first real scan is
     * not the one that discovers the model is missing.
     */
    /**
     * Warm ML Kit only.
     *
     * PP-OCR deliberately stays unloaded here. Its three sessions cost tens of
     * megabytes of native heap for the life of the process, and the process
     * spends most of its life being a microphone at 3am -- which is exactly
     * when a phone with an aggressive memory manager decides which background
     * service to kill. It loads on the first scan instead; see [prepare].
     */
    suspend fun warmUp() {
        // ML Kit fetches its models over the network on first use, so it needs a
        // real image put through it early.
        // Deliberately not scan(): that would escalate through seven passes on a
        // blank probe, and PP-OCR would upsample 32 pixels to 736 to find
        // nothing in it.
        val probe = android.graphics.Bitmap.createBitmap(
            64, 64, android.graphics.Bitmap.Config.ARGB_8888
        )
        val image = InputImage.fromBitmap(probe, 0)
        runCatching { recognise(image, latin) }
        runCatching { recognise(image, devanagari) }
        probe.recycle()
    }

    /**
     * Load PP-OCR now, because a scan is about to happen.
     *
     * Called when the Scan screen opens, so the half-second of session
     * construction overlaps with the user aiming the camera rather than landing
     * on the shutter press.
     */
    fun prepare() { runCatching { paddle.load() } }

    /** Release PP-OCR's sessions. The next scan reloads them. */
    fun releaseOcr() { runCatching { paddle.close() } }

    /** What the OCR stack is, for the settings screen. */
    fun describe(): String = paddle.describe()

    /**
     * Read a photograph, escalating through harder attempts until it works.
     *
     * A single pass over the raw bitmap fails on exactly the images people
     * actually take: a strip shot at an angle, a box with a diagonal watermark
     * across it, silver foil with white print. Each pass below is cheap and
     * targets a different one of those, and the sequence stops as soon as
     * something recognisable comes back -- so a clear photo still costs one
     * pass, and only a difficult one pays for the rest.
     *
     * Lines from every pass are pooled rather than replaced. Passes disagree
     * about different parts of the same label, and the union reads more of it
     * than any single pass does.
     */
    suspend fun scan(bitmap: Bitmap): Scan {
        val pooled = LinkedHashSet<String>()
        var blocks = 0
        var everReady = false
        var passes = 0

        /**
         * A recogniser that has already failed to answer once.
         *
         * Cancelling the wait does not cancel ML Kit's work, so when one call
         * does not come back the engine is still busy with it. Starting the
         * next pass on the same recogniser would queue behind that and time out
         * too, turning one slow pass into seven. Once a recogniser has missed
         * its deadline it is not asked again for this image; the other one, and
         * whatever PP-OCR already read, still count.
         */
        val wedged = mutableSetOf<com.google.mlkit.vision.text.TextRecognizer>()

        suspend fun attempt(candidate: Bitmap, label: String): Boolean {
            passes++
            val image = InputImage.fromBitmap(candidate, 0)
            val latinResult = read(image, latin, wedged)
            val devaResult = read(image, devanagari, wedged)
            if (!latinResult.modelMissing || !devaResult.modelMissing) everReady = true
            blocks += latinResult.blocks + devaResult.blocks

            val found = (latinResult.lines + devaResult.lines)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            pooled += found
            Log.d(TAG, "pass '$label': ${found.size} lines")
            return looksSufficient(pooled)
        }

        val sized = ImagePrep.normaliseSize(bitmap)
        try {
            // PP-OCR first, on the untouched image. Its detector does its own
            // rescaling and its angle classifier does its own rotation, so the
            // contrast and rotation passes below exist for ML Kit, which cannot.
            passes++
            val paddleResult = runCatching { paddle.read(sized) }
                .onFailure { Log.w(TAG, "PP-OCR pass failed", it) }
                .getOrNull()
            if (paddleResult != null) {
                everReady = true
                blocks += paddleResult.boxesFound
                pooled += paddleResult.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
                Log.d(TAG, "pass 'pp-ocr': ${paddleResult.lines.size} lines " +
                    "from ${paddleResult.boxesFound} boxes in ${paddleResult.elapsedMs}ms")
                if (looksSufficient(pooled)) return finish(pooled, blocks, everReady, passes)
            }

            if (attempt(sized, "original")) return finish(pooled, blocks, everReady, passes)

            val enhanced = ImagePrep.sharpen(ImagePrep.enhance(sized))
            try {
                if (attempt(enhanced, "contrast+sharpen")) {
                    return finish(pooled, blocks, everReady, passes)
                }

                // A centre crop gives small print more pixels per character,
                // which is usually what rescues a box shot taken from a distance.
                val cropped = ImagePrep.centreCrop(enhanced)
                try {
                    if (attempt(cropped, "centre crop")) {
                        return finish(pooled, blocks, everReady, passes)
                    }
                } finally {
                    ImagePrep.recycleIfDerived(cropped, enhanced)
                }

                // Last resort: the strip was photographed sideways. ML Kit has
                // no angle classifier, so the rotation has to be tried by hand.
                for (angle in listOf(270f, 90f, 180f)) {
                    val turned = ImagePrep.rotate(enhanced, angle)
                    try {
                        if (attempt(turned, "rotated ${angle.toInt()}")) {
                            return finish(pooled, blocks, everReady, passes)
                        }
                    } finally {
                        ImagePrep.recycleIfDerived(turned, enhanced)
                    }
                }
            } finally {
                ImagePrep.recycleIfDerived(enhanced, sized)
            }
        } finally {
            ImagePrep.recycleIfDerived(sized, bitmap)
        }

        return finish(pooled, blocks, everReady, passes)
    }

    /**
     * Enough to stop trying.
     *
     * Deliberately not "any text at all": a watermark URL alone satisfies that
     * and would stop the search before the medicine name was ever read.
     */
    private fun looksSufficient(pooled: Set<String>): Boolean {
        // Character count, not line count. A label reading "PARACETAMOL Tablets
        // IP 500 mg" on one line is entirely sufficient, and demanding two lines
        // made the clean case pay for four extra passes it never needed.
        //
        // The watermark filter runs first, so a supplier URL contributes nothing
        // here -- which is what stops a stock photo's watermark from ending the
        // search before the medicine name has been read.
        return clean(pooled).sumOf { it.length } >= 18
    }

    /**
     * Drop the text that is on the packet but is not the medicine.
     *
     * Stock photos of medicine boxes are routinely watermarked with a supplier's
     * web address, and those tokens are long, high-contrast and read perfectly --
     * so they crowd out the small printed name they are sitting on top of.
     */
    private fun clean(lines: Collection<String>): List<String> {
        val noise = Regex(
            """^(www\.|https?://)|\.(com|in|net|org)\b|^\W+$|^\d{1,3}$""",
            RegexOption.IGNORE_CASE,
        )
        return lines
            .map { it.trim() }
            .filter { it.length > 1 && !noise.containsMatchIn(it) }
            .distinct()
    }

    private fun finish(
        pooled: Set<String>,
        blocks: Int,
        everReady: Boolean,
        passes: Int,
    ): Scan {
        val lines = clean(pooled)
        Log.i(TAG, "scan finished after $passes pass(es), ${lines.size} usable lines")
        return Scan(
            text = lines.joinToString(" "),
            lines = lines,
            blocks = blocks,
            // Only a problem if no recogniser was ever ready; one script
            // reading the label is enough.
            modelNotReady = !everReady,
            passes = passes,
        )
    }

    private data class Partial(
        val lines: List<String>,
        val blocks: Int,
        val modelMissing: Boolean,
    )

    /**
     * The one [Partial] that means "it never answered".
     *
     * Compared by identity, so it is distinguishable from a recogniser that
     * genuinely found nothing -- which is a normal, frequent result and must
     * not take a recogniser out of the rest of the scan.
     */
    private val TIMED_OUT = Partial(emptyList(), 0, false)

    /**
     * One recogniser, one image, a deadline, and a memory of past failures.
     *
     * ML Kit answers through a listener, and a listener that is never called
     * is indistinguishable from one that is slow. Nothing in this lane had a
     * timeout, so a wait like that would never have ended -- and on the Scan
     * screen an unending wait is a spinner under "Reading the label" that stays
     * there, with no error and no verdict.
     *
     * Honest about provenance: the hang that prompted this turned out to be in
     * PP-OCR rather than here (see [com.nila.ocr.PaddleOcr]'s recogniser width
     * cap). This guard stayed anyway. An unbounded wait on a third-party
     * callback, in the one path somebody is sitting and watching, is worth
     * bounding on its own account.
     *
     * A pass that runs out of time contributes nothing and the next one is
     * tried; the lines already pooled from earlier passes are kept. Failing a
     * pass is survivable, which is the whole reason the escalation exists.
     */
    private suspend fun read(
        image: InputImage,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        wedged: MutableSet<com.google.mlkit.vision.text.TextRecognizer>,
    ): Partial {
        if (recognizer in wedged) return Partial(emptyList(), 0, false)
        val result = recognise(image, recognizer)
        if (result === TIMED_OUT) {
            wedged += recognizer
            Log.w(TAG, "a recogniser missed ${PASS_TIMEOUT_MS}ms; " +
                "skipping it for the rest of this image")
        }
        return result
    }

    private suspend fun recognise(
        image: InputImage,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): Partial = withTimeoutOrNull(PASS_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.map { it.text }
                    }
                    if (cont.isActive) cont.resume(Partial(lines, result.textBlocks.size, false))
                }
                .addOnFailureListener { error ->
                    // A recogniser failing is not fatal -- the other may still
                    // read the strip. But "model still downloading" is a
                    // different state from "no text", and the caller needs to
                    // tell them apart.
                    val missing = (error as? MlKitException)?.errorCode ==
                        MlKitException.UNAVAILABLE
                    if (cont.isActive) cont.resume(Partial(emptyList(), 0, missing))
                }
        }
    } ?: TIMED_OUT

    fun close() {
        // Not paddle: it is shared with every other scanner in the process.
        runCatching { latin.close() }
        runCatching { devanagari.close() }
    }
}
