package com.nila

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.TextScanner
import com.nila.assistant.agents.MedicineReviewPipeline
import com.nila.data.HealthRecord
import com.nila.data.HealthRecordDao
import com.nila.data.MotherProfile
import com.nila.data.NilaDatabase
import com.nila.data.RecordStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Thirty photographs of real medicine packaging, scanned on the phone.
 *
 * These are not rendered strips. They are Wikimedia photographs of blister
 * foil, cartons in Cyrillic and Japanese, tablets with the name debossed into
 * them and one shop shelf with four other products in frame -- chosen, in
 * `data/README.md`, for the specific ways they defeat OCR. The folder each
 * image sits in is the verdict the app is supposed to reach, taken from the
 * `risk` field in `knowledge.json`.
 *
 * The images are pushed to the device rather than bundled: they are 16 MB and
 * several carry a share-alike licence, neither of which belongs in an APK.
 *
 *   adb push data/tablet_sheets /sdcard/Download/nila_tablet_sheets
 *
 * Download rather than the app's own `Android/media` folder because this phone
 * deleted that one between the push and the test run -- vivo's storage manager
 * reclaims media directories belonging to packages with no data directory, and
 * a fixture that disappears halfway through a verification run is worse than
 * one that lives somewhere boring.
 *
 * Getting the bytes into the app takes one hop. The app cannot read
 * `/sdcard/Download` -- that needs `READ_MEDIA_IMAGES`, and adding a permission
 * to a shipped app so a test can find its fixtures is the wrong way round -- so
 * the instrumentation's shell uid copies the folder into the app's own external
 * files directory once, and everything after that is ordinary file IO.
 *
 * It copies rather than streaming each image through `executeShellCommand`.
 * That was the first attempt and it deadlocked on the ninth photograph: the
 * returned pipe has a fixed buffer, and a 736 KB JPEG fills it while the reader
 * is still on the same thread that has to service the connection. The symptom
 * was a test at 0% CPU with no output and nothing in logcat, which is a bad
 * half-hour to hand anyone.
 *
 * The test skips itself when the images are not there, so a CI run without
 * them is a pass rather than a false alarm.
 */
@RunWith(AndroidJUnit4::class)
class TabletSheetScanTest {

    private companion object { const val STARTED = "..." }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scanner: TextScanner
    private lateinit var index: KnowledgeIndex
    private lateinit var pipeline: MedicineReviewPipeline
    private lateinit var root: File
    private val store by lazy { RecordStore(context) }

    @Before
    fun setUp() = runBlocking {
        root = stageFixtures() ?: File("/nonexistent")
        assumeTrue("tablet_sheets not readable on the device",
                   File(root, "manifest.json").canRead())
        scanner = TextScanner(context)
        scanner.warmUp()
        scanner.prepare()   // loads the shared PP-OCR sessions once
        index = KnowledgeIndex.load(context)
        // No stored records. The manifest's ground truth is the verdict the
        // *corpus* implies, and the phone this runs on has the demo seed in it
        // -- whose mother has a penicillin allergy on file. The app was right to
        // return CAUTION for amoxicillin because of it, and that is exactly why
        // the personal layer has to be held out of a comparison against a table
        // that knows nothing about her.
        val db = NilaDatabase.get(context)
        val noRecords = object : HealthRecordDao by db.healthRecords() {
            override suspend fun listForSubject(subject: String) = emptyList<HealthRecord>()
        }
        pipeline = MedicineReviewPipeline(
            index, noRecords, com.nila.ddi.DrugInteractionLookupImpl(context),
        )
    }

    @After
    fun tearDown() {
        if (this::scanner.isInitialized) scanner.close()
    }

    /**
     * Run one command as the shell user and return what it printed.
     *
     * The command is passed unquoted on purpose: this does not go through a
     * shell, it splits on whitespace and execs, so quoting an argument makes
     * the quotes part of the filename. That cost an hour -- every read came
     * back empty and it looked exactly like a missing fixture.
     */
    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor = automation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .use { String(it.readBytes()) }
    }

    /** Copy the pushed fixtures where the app can read them, once. */
    private fun stageFixtures(): File? {
        val destination = File(context.getExternalFilesDir(null), "tablet_sheets")
        if (File(destination, "manifest.json").canRead()) return destination
        val source = listOf(
            "/sdcard/Download/nila_tablet_sheets",
            "/storage/emulated/0/Download/nila_tablet_sheets",
        ).firstOrNull { shell("ls $it/manifest.json").contains("manifest.json") }
            ?: return null
        destination.mkdirs()
        shell("cp -r $source/. ${destination.absolutePath}")
        Log.i("TabletScan", "staged fixtures from $source to $destination")
        return destination
    }

    private data class Case(val file: File, val name: String,
                            val generic: String, val expected: String)

    private fun cases(): List<Case> {
        val manifest = File(root, "manifest.json").takeIf { it.canRead() } ?: return emptyList()
        val images = JSONObject(manifest.readText()).getJSONArray("images")
        return (0 until images.length()).map { i ->
            val entry = images.getJSONObject(i)
            // The manifest stores paths relative to data/, e.g.
            // "tablet_sheets/safe/foo.jpg"; on the device the folder is the root.
            val relative = entry.getString("file").substringAfter("tablet_sheets/")
            Case(File(root, relative), relative.substringAfterLast('/'),
                 entry.getString("generic"), entry.getString("expected_verdict"))
        }
    }

    /**
     * One line per photograph, written as it is scanned.
     *
     * Not a convenience. This phone throttles logcat for ordinary apps, so a
     * run's results are not reliably readable afterwards, and a process death
     * halfway through used to lose everything that came before it. A file the
     * run appends to survives both -- and is what lets a crashed run be
     * restarted where it stopped rather than from the beginning.
     */
    private val report by lazy { File(context.getExternalFilesDir(null), "scan_report.tsv") }

    /**
     * Where the report lives between runs.
     *
     * The app's own directory is not good enough: Gradle uninstalls the app
     * when `connectedAndroidTest` finishes, and uninstalling takes
     * `Android/data/com.nila` with it. Every run was therefore starting from
     * the first photograph however many the last one had got through, which is
     * the opposite of what a resumable report is for. This copy is in shared
     * storage, written by the shell, and survives.
     */
    private val keep = "/sdcard/Download/nila_scan_report.tsv"

    private var writes = 0

    private fun record(line: String) {
        report.appendText(line)
        // Best effort, and deliberately not on every line -- a shell round
        // trip per write was enough to take the process down. Every tenth
        // line, and once more when the run ends, is enough to resume from.
        writes++
        if (writes % 10 == 0) runCatching { shell("cp ${report.absolutePath} $keep") }
    }

    /**
     * What previous runs got to, last word wins.
     *
     * A name left on [STARTED] is one that never came back -- the process died
     * or a native call blocked where a coroutine timeout cannot reach it. It is
     * recorded as a failure of that photograph so the next run steps over it
     * instead of stopping in the same place.
     */
    private fun recorded(): Map<String, String> {
        // Seed from the surviving copy, if a previous run left one.
        if (!report.exists()) {
            val previous = runCatching { shell("cat $keep") }.getOrDefault("")
            if (previous.contains('\t')) runCatching { report.writeText(previous) }
        }
        return if (!report.exists()) emptyMap()
        else report.readLines()
            .filterNot { it.startsWith("#") }
            .mapNotNull { line ->
                line.split('\t').takeIf { it.size >= 3 }?.let { it[0] to it[2] }
            }
            .toMap()
            .mapValues { (_, outcome) -> if (outcome == STARTED) "-|STALLED" else outcome }
    }

    /**
     * Write every thread's stack into the report if an image takes too long.
     *
     * The only way to find out where a scan stops. This phone throttles logcat
     * for ordinary apps and will not let the shell signal another uid, so the
     * usual ways of getting a thread dump are both closed -- but the test runs
     * *inside* the app process, so it can dump its own.
     */
    private fun watchdog(case: Case): Thread =
        Thread {
            try {
                Thread.sleep(90_000)
                val dump = Thread.getAllStackTraces()
                    .filterKeys { it.name.startsWith("Instr") || it.state ==
                        Thread.State.RUNNABLE || it.name.contains("gle") }
                    .entries.joinToString("\n") { (t, frames) ->
                        "  ${t.name} [${t.state}]\n" +
                            frames.take(12).joinToString("\n") { "      at $it" }
                    }
                record("# STUCK on ${case.name}\n$dump\n")
            } catch (_: InterruptedException) {
                // finished in time, which is the normal case
            }
        }.apply { isDaemon = true; start() }

    /** Scan one photograph, as "<generic>|<VERDICT>". */
    private suspend fun scan(case: Case, profile: MotherProfile): String {
        val watch = watchdog(case)
        try {
            return scanInner(case, profile)
        } finally {
            watch.interrupt()
        }
    }

    private suspend fun scanInner(case: Case, profile: MotherProfile): String {
        // The app's own decode, not a raw one. Several of these are 12
        // megapixels and RecordStore downsamples on the way in; decoding them
        // at full size is both unfaithful to the scan lane and enough to get
        // the process killed halfway through the set, which is how this was
        // first written.
        val bitmap = store.decodeUri(Uri.fromFile(case.file))
            ?: return "-|UNDECODABLE"
        // A recogniser per photograph, closed afterwards.
        //
        // Cancelling a wait does not cancel ML Kit's work, so a pass that
        // missed its deadline leaves the engine still busy with an image
        // nobody wants any more. One scan is unaffected -- a person photographs
        // one strip and then reads the answer -- but thirty in a row pile that
        // abandoned work up behind a single client until nothing moves: 0% CPU,
        // no progress, and it looked exactly like the hang this is testing.
        // Closing the client after each image is what a day of real use does
        // anyway, spread out.
        val scanner = TextScanner(context)
        try {
            // Bounded. ML Kit's recognisers hand back their result through a
            // listener, and a listener that never fires is indistinguishable
            // from a slow one -- a stalled recogniser took the whole run down
            // twice before this was here, silently, at 0% CPU. A photograph
            // that cannot be read in a minute is a failure of that photograph.
            val text = withTimeoutOrNull(60_000) {
                runCatching { scanner.scan(bitmap).text }.getOrDefault("")
            } ?: "".also { Log.w("TabletScan", "${case.name}: OCR timed out") }
            val result = pipeline.review(text, profile)
            return "${result.medicine?.generic ?: "-"}|${result.verdict.name}"
        } finally {
            runCatching { scanner.close() }
            bitmap.recycle()
            System.gc()
        }
    }

    @Test
    fun everyPhotographReachesTheVerdictItsFolderClaims() = runBlocking {
        val cases = cases()
        assumeTrue("manifest listed no readable images", cases.isNotEmpty())

        // A mother with nothing on file, so the verdict is the corpus answer
        // and not a personal one. That is what the ground truth describes.
        val profile = MotherProfile(isBreastfeeding = true)

        val done = recorded()
        var identified = 0
        var correct = 0
        val wrong = mutableListOf<String>()

        for (case in cases) {
            val previous = done[case.name]
            val outcome = if (previous != null) previous else {
                record("${case.name}\t${case.generic}\t$STARTED\n")
                scan(case, profile).also {
                    record("${case.name}\t${case.generic}\t$it\n")
                }
            }

            val name = outcome.substringBefore('|')
            val got = outcome.substringAfter('|')
            if (name.equals(case.generic, ignoreCase = true)) identified++
            if (got == case.expected) correct++ else
                wrong += "${case.name}: $name/$got, " +
                    "expected ${case.generic}/${case.expected}"
            Log.i("TabletScan", "${case.name} -> $name / $got " +
                "(expected ${case.generic} / ${case.expected})")
        }

        val summary = "identified $identified/${cases.size}, " +
            "verdict correct $correct/${cases.size}"
        Log.i("TabletScan", summary)
        record("# $summary\n")
        runCatching { shell("cp ${report.absolutePath} $keep") }
        wrong.forEach { Log.w("TabletScan", "MISS $it") }

        // The safety property, and the one worth failing a build over: a
        // photograph may be unreadable, but it must never come back as a
        // different medicine. A fluconazole carton reported as paracetamol is
        // worse than a fluconazole carton reported as nothing, and this set has
        // caught that twice.
        val wrongDrug = wrong.filterNot { it.contains("/UNKNOWN") ||
            it.contains("UNDECODABLE") || it.contains("STALLED") }
        assertTrue("a photograph was read as the wrong medicine:\n" +
            wrongDrug.joinToString("\n"), wrongDrug.isEmpty())

        // And the level the set actually reaches: 19 to 20 of 30 across runs,
        // every miss an honest "could not read it" rather than a wrong answer.
        // Several images are here precisely because they are hard -- a name
        // debossed into a bare tablet, Cyrillic and Japanese cartons, and one
        // deliberate negative with no readable text at all -- so a perfect
        // score is the wrong thing to demand.
        //
        // The floor sits below the observed range on purpose. OCR is not
        // deterministic run to run and one borderline image crosses the line in
        // either direction, so a threshold set at the best observed score would
        // fail on nothing having changed. It is here to catch a change that
        // quietly loses several, which is what it would take to breach 18.
        assertTrue(
            "verdicts correct on only $correct of ${cases.size}:\n" +
                wrong.joinToString("\n"),
            correct >= 18,
        )
    }
}
