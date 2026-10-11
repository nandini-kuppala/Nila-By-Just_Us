package com.nila.babyproof.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nila.babyproof.pipeline.BabyproofPipeline
import com.nila.babyproof.pipeline.Scan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val photo: Bitmap? = null,
        val photoName: String = "",
        val scan: Scan? = null,
        val running: Boolean = false,
        val step: String = "",
        val error: String? = null,
        val full: Boolean = true,
    )

    private val pipeline = BabyproofPipeline(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val samples: List<String> = runCatching {
        app.assets.list("babyproof/samples").orEmpty().filter { it.endsWith(".jpg") }.sorted()
    }.getOrDefault(emptyList())

    fun modelsInstalled() = pipeline.detector.isInstalled() to pipeline.vlm.isInstalled()

    fun setFull(full: Boolean) { _state.value = _state.value.copy(full = full) }

    fun scanUri(uri: Uri) = start("Your photo") { pipeline.decode { getApplication<Application>().contentResolver.openInputStream(uri)!! } }

    fun scanSample(name: String) = start(name.substringBeforeLast('.').replace('-', ' ')) {
        pipeline.decode { getApplication<Application>().assets.open("babyproof/samples/$name") }
    }

    private fun start(name: String, load: () -> Bitmap) {
        if (_state.value.running) return
        viewModelScope.launch {
            val full = _state.value.full
            try {
                val photo = withContext(Dispatchers.IO) { load() }
                _state.value = _state.value.copy(photo = photo, photoName = name, scan = null, running = true, step = "Starting", error = null)
                val scan = pipeline.scan(photo, if (full) BabyproofPipeline.Mode.FULL else BabyproofPipeline.Mode.DETECTOR) { step ->
                    _state.value = _state.value.copy(step = step)
                }
                _state.value = _state.value.copy(scan = scan, running = false, step = "")
            } catch (t: Throwable) {
                Log.e("Babyproof", "scan failed", t)
                _state.value = _state.value.copy(running = false, step = "", error = t.message ?: t.toString())
            }
        }
    }

    override fun onCleared() {
        pipeline.closeVlm()
        pipeline.detector.close()
    }
}
