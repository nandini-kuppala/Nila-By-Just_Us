package com.nila.visit

import android.content.Context
import android.util.Log
import com.nila.assistant.ModelInstaller
import com.nila.visit.pipeline.VisitPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * What the doctor visit needs on the phone, and getting it there.
 *
 * Two models. The chat model is Ask's own GGUF -- one download serves both.
 * Whisper is AI Hub's zip: downloaded with [ModelInstaller] like every other
 * model in the app, then unpacked here, and given the two small float-typed
 * wrappers from the APK's assets (ONNX Runtime's Java API has no uint16, see
 * tools/patch_whisper.py in the lab).
 */
class VisitModels(private val context: Context) {

    companion object {
        private const val TAG = "VisitModels"
        /** The zip's files that are kept; the rest of it is not needed. */
        private val KEEP = setOf(
            "metadata.json", "encoder.onnx", "decoder.onnx",
            "encoder_qairt_context.bin", "decoder_qairt_context.bin",
        )
    }

    sealed interface State {
        data object Ready : State
        /** Whisper is here; the chat model Ask downloads is not. */
        data object NeedsChatModel : State
        data object Absent : State
        data class Downloading(val percent: Int) : State
        data object Unpacking : State
        data class Failed(val reason: String) : State
    }

    private val pipeline = VisitPipeline(context)
    private val installer = ModelInstaller(context, ModelInstaller.Model.LISTENING)

    private fun zip() = File(context.getExternalFilesDir("models"), ModelInstaller.Model.LISTENING.fileName)

    suspend fun state(): State {
        // The folder is made by the app, never by hand: on Android 16 a folder
        // created over adb under Android/data cannot be read by its own app.
        pipeline.whisperDir("turbo").mkdirs()
        if (pipeline.whisperInstalled()) {
            return if (pipeline.llmInstalled()) State.Ready else State.NeedsChatModel
        }
        return when (val s = installer.state()) {
            is ModelInstaller.State.Downloading -> State.Downloading(s.percent)
            is ModelInstaller.State.Failed -> State.Failed(s.reason)
            ModelInstaller.State.Installed -> if (zip().length() > 0) State.Unpacking else State.Absent
            ModelInstaller.State.Absent -> State.Absent
        }
    }

    suspend fun download(): Boolean = installer.start()

    suspend fun cancel() = installer.cancel()

    /** Unzip the downloaded bundle into models/whisper-turbo and add the wrappers. */
    suspend fun unpack(): Boolean = withContext(Dispatchers.IO) {
        val zip = zip()
        if (!zip.exists()) return@withContext false
        val dir = pipeline.whisperDir("turbo").apply { mkdirs() }
        try {
            ZipInputStream(zip.inputStream().buffered(1 shl 20)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || name !in KEEP) continue
                    File(dir, name).outputStream().buffered(1 shl 20).use { z.copyTo(it, 1 shl 20) }
                }
            }
            listOf("encoder_f.onnx", "decoder_f.onnx").forEach { name ->
                context.assets.open("visit/whisper-turbo/$name").use { i ->
                    File(dir, name).outputStream().use { i.copyTo(it) }
                }
            }
            // Removes the download record and the zip with it.
            installer.cancel()
            Log.i(TAG, "whisper unpacked into $dir")
            pipeline.whisperInstalled()
        } catch (t: Throwable) {
            Log.e(TAG, "unpack failed", t)
            false
        }
    }

    /**
     * The sample visits, copied out of the APK on first use so they can be
     * read and replayed as plain files like a recorded visit.
     */
    fun samples(): List<JSONObject> = runCatching {
        val dir = sampleDir()
        val a = JSONObject(File(dir, "visits.json").readText()).getJSONArray("visits")
        (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())

    fun sampleAudio(id: String): File = File(sampleDir(), "$id.wav")

    private fun sampleDir(): File {
        val dir = File(context.filesDir, "visit/samples")
        // Copied again whenever the app is updated, so a re-recorded sample
        // reaches a phone that already has the old one.
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        val marker = File(dir, ".copied")
        if (!marker.exists() || marker.readText() != stamp) {
            dir.mkdirs()
            context.assets.list("visit/samples").orEmpty().forEach { name ->
                context.assets.open("visit/samples/$name").use { i ->
                    File(dir, name).outputStream().use { i.copyTo(it) }
                }
            }
            marker.writeText(stamp)
        }
        return dir
    }
}
