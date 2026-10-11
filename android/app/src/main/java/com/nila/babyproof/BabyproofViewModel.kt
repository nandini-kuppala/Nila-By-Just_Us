package com.nila.babyproof

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nila.monitor.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The babyproofing page: a photo of a room in, a list of what to fix out.
 *
 * Held in a view model so a scan in progress survives the screen rotating, and
 * the list survives a trip to another tab while the parent goes and fixes things.
 */
class BabyproofViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object Idle : Phase
        data class Working(val photo: Bitmap, val step: String, val progress: Float) : Phase
        data class Done(val photo: Bitmap, val scan: Scan, val sample: Sample?) : Phase
        data class Failed(val message: String) : Phase
    }

    /** A built-in room to try it on, with the credit its licence asks for. */
    data class Sample(val file: String, val title: String, val credit: String, val url: String)

    private val pipeline = BabyproofPipeline(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Findings the parent has ticked off, by their place in the list. */
    private val _fixed = MutableStateFlow<Set<Int>>(emptySet())
    val fixed: StateFlow<Set<Int>> = _fixed.asStateFlow()

    val samples: List<Sample> = runCatching {
        val a = JSONObject(app.assets.open("babyproof/samples/samples.json").bufferedReader().readText())
            .getJSONArray("samples")
        (0 until a.length()).map { i ->
            a.getJSONObject(i).run { Sample(getString("file"), getString("title"), getString("credit"), getString("url")) }
        }
    }.getOrDefault(emptyList())

    val detectorInstalled: Boolean get() = pipeline.detectorInstalled()
    val visionModelInstalled: Boolean get() = pipeline.visionModelInstalled()

    /**
     * The watch and a room scan both need the phone's AI chip. The watch keeps
     * it, and the page asks the parent to pause the watch first.
     */
    val watchRunning: Boolean get() = WatchService.state.value.running

    private var job: Job? = null

    private companion object {
        /** Below this mean brightness a room photo is too dark to judge (a dim evening room is about 0.2). */
        const val TOO_DARK = 0.08f
    }

    /** Where the camera writes a full-size photo (a preview thumbnail is too small to find a socket in). */
    fun cameraTarget(): Uri {
        val app = getApplication<Application>()
        val file = File(File(app.cacheDir, "babyproof").apply { mkdirs() }, "room.jpg")
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    fun scanCameraPhoto() {
        val file = File(File(getApplication<Application>().cacheDir, "babyproof"), "room.jpg")
        run(null) { pipeline.decode { file.inputStream() } }
    }

    fun scanUri(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        run(null) { pipeline.decode { resolver.openInputStream(uri) ?: error("could not open the picture") } }
    }

    fun scanSample(sample: Sample) {
        val assets = getApplication<Application>().assets
        run(sample) { pipeline.decode { assets.open("babyproof/samples/${sample.file}") } }
    }

    private fun run(sample: Sample?, decode: () -> Bitmap) {
        if (job?.isActive == true) return
        _fixed.value = emptySet()
        job = viewModelScope.launch {
            val photo = runCatching { withContext(Dispatchers.IO) { decode() } }.getOrElse {
                _phase.value = Phase.Failed("That picture could not be opened. Try another one.")
                return@launch
            }
            // On a black photo the vision model makes things up ("toys"); say so instead.
            if (brightness(photo) < TOO_DARK) {
                _phase.value = Phase.Failed("This photo is too dark to check. Turn the lights on, or try again in daylight.")
                return@launch
            }
            _phase.value = Phase.Working(photo, "Getting the phone ready", 0f)
            runCatching {
                pipeline.scan(photo) { step, progress -> _phase.value = Phase.Working(photo, step, progress) }
            }.onSuccess { scan ->
                Log.i("Babyproof", "scan: ${scan.findings.size} findings, ${scan.timings}")
                _phase.value = Phase.Done(photo, scan, sample)
            }.onFailure {
                Log.e("Babyproof", "scan failed", it)
                _phase.value = Phase.Failed("Something went wrong while checking the room. Please try again.")
            }
        }
    }

    /** Mean luminance, 0..1, from a 32 x 32 thumbnail. */
    private fun brightness(photo: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(photo, 32, 32, true)
        val px = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
        return px.map { c -> (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f }.average().toFloat()
    }

    fun toggleFixed(index: Int) {
        _fixed.value = _fixed.value.let { if (index in it) it - index else it + index }
    }

    fun reset() {
        if (job?.isActive == true) return
        _phase.value = Phase.Idle
        _fixed.value = emptySet()
    }
}
