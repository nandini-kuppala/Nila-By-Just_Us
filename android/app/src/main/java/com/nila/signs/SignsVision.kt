package com.nila.signs

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nila.monitor.WatchService
import com.nila.skin.ai.VisionModel
import com.nila.vision.guard.RemoteVlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Little Signs' vision model inside Nila: the same Intern3.5-VL the watch uses,
 * in its own process through [RemoteVlm], so there is never a second copy on the
 * NPU (two models resident on the NPU crash it), and the chat model is moved off
 * first by the NPU arbiter, as for every other vision load.
 *
 * While the safety watch is running the model is the watch's: Little Signs gets
 * no description then (its maths and checklists still work) and never closes it.
 * Otherwise the model is let go after a minute without a look, so the chat model
 * can have the NPU back.
 */
class SignsVision(private val context: Context) : VisionModel {

    private companion object {
        const val TAG = "SignsVision"
        const val IDLE_MS = 60_000L
    }

    private val _status = MutableStateFlow<VisionModel.Status>(
        if (installed()) VisionModel.Status.Installed else VisionModel.Status.Absent
    )
    override val status: StateFlow<VisionModel.Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var remote: RemoteVlm? = null
    private var idle: Job? = null

    private fun installed() = RemoteVlm(context, {}, {}, {}).isInstalled()

    private fun watchRunning() = WatchService.state.value.running

    override suspend fun ensureLoaded(): Boolean = lock.withLock {
        if (!installed()) { _status.value = VisionModel.Status.Absent; return@withLock false }
        if (watchRunning()) {
            _status.value = VisionModel.Status.Failed("the safety watch is using the vision model")
            return@withLock false
        }
        remote?.takeIf { it.ready }?.let { return@withLock true }
        _status.value = VisionModel.Status.Loading(1, 3)
        val started = System.currentTimeMillis()
        val r = RemoteVlm(context, log = { Log.i(TAG, it) }, onState = {}, onDied = {
            remote = null
            _status.value = VisionModel.Status.Installed
        })
        var ok = false
        for (attempt in 1..3) {
            _status.value = VisionModel.Status.Loading(attempt, 3)
            ok = r.load()
            if (ok) break
            delay(1_500)
        }
        if (!ok) {
            r.close()
            _status.value = VisionModel.Status.Failed("the vision model did not load")
            return@withLock false
        }
        remote = r
        _status.value = VisionModel.Status.Ready("Intern3.5-VL-2B", System.currentTimeMillis() - started)
        true
    }

    override suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String? {
        if (!ensureLoaded()) return null
        idle?.cancel()
        val answer = remote?.look(frame, systemPrompt, userPrompt)
        idle = scope.launch { delay(IDLE_MS); close() }
        return answer
    }

    override fun close() {
        idle?.cancel()
        val r = remote ?: return
        remote = null
        // Never take the model away from a running watch.
        if (!watchRunning()) r.close()
        _status.value = if (installed()) VisionModel.Status.Installed else VisionModel.Status.Absent
    }
}
