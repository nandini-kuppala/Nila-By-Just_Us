package com.nila.skin.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Intern3.5-VL-2B on the Hexagon NPU, through Qualcomm's GenieX runtime.
 *
 * The same compiled bundle Nila's safety watch uses (AI Hub, w4a16, Snapdragon
 * 8 Elite Gen 5), and the same loading lessons, taken from Nila's NpuVlm:
 * the first create() in a process often fails while the DSP maps 2.6 GB, so
 * loading is a retry ladder; a retry that once crashed the process is not
 * retried again; and only one model may be resident on the NPU, which a host
 * enforces through [NpuGate].
 *
 * Measured in Nila: ~490 ms to first token, ~1.2 s for a short answer.
 */
class NpuVisionModel(
    private val context: Context,
    private val gate: NpuGate = NpuGate.Open,
) : VisionModel {

    companion object {
        private const val TAG = "SkinVlm"
        const val MODEL_NAME = "local/Intern3.5-VL-2B"
        const val PRECISION = "w4a16"
        const val BUNDLE_DIR = "intern-vl"
        private val REQUIRED = listOf("metadata.json", "vision_encoder.bin", "part1_of_4.bin", "part4_of_4.bin", "tokenizer.json")
        private val RETRY_WAITS_MS = longArrayOf(0, 2_000, 4_000, 8_000, 8_000, 8_000)
        private const val RETRY_IN_FLIGHT = "retry_in_flight"
        private const val MAX_TOKENS = 120
    }

    private val _status = MutableStateFlow<VisionModel.Status>(if (isInstalled()) VisionModel.Status.Installed else VisionModel.Status.Absent)
    override val status: StateFlow<VisionModel.Status> = _status

    private var vlm: VlmWrapper? = null
    private val lock = Mutex()
    private val guard = context.getSharedPreferences("skin_vlm", Context.MODE_PRIVATE)
    private val worker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nila-skin-vlm") }.asCoroutineDispatcher()

    fun bundleDir(): File {
        val external = File(context.getExternalFilesDir("models"), BUNDLE_DIR)
        if (external.isDirectory) return external
        return File(File(context.filesDir, "local"), BUNDLE_DIR)
    }

    fun isInstalled(): Boolean {
        val dir = bundleDir()
        return dir.isDirectory && REQUIRED.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    override suspend fun ensureLoaded(): Boolean = lock.withLock {
        if (vlm != null) return@withLock true
        if (!isInstalled()) { _status.value = VisionModel.Status.Absent; return@withLock false }
        val ok = runCatching { initSdk() }.onFailure { Log.i(TAG, "GenieX unavailable: ${it.message}") }.getOrDefault(false)
        if (!ok) { _status.value = VisionModel.Status.Failed("GenieX did not start on this phone"); return@withLock false }
        val paths = runCatching { registerBundle() }.onFailure { Log.w(TAG, "register failed", it) }.getOrNull()
        if (paths == null) { _status.value = VisionModel.Status.Failed("the model bundle could not be registered"); return@withLock false }
        if (!gate.claimForLoad()) { _status.value = VisionModel.Status.Installed; return@withLock false }

        val crashed = guard.getBoolean(RETRY_IN_FLIGHT, false)
        if (crashed) guard.edit().putBoolean(RETRY_IN_FLIGHT, false).commit()
        val waits = if (crashed) RETRY_WAITS_MS.copyOf(1) else RETRY_WAITS_MS
        val started = System.currentTimeMillis()
        for ((i, wait) in waits.withIndex()) {
            if (wait > 0) delay(wait)
            _status.value = VisionModel.Status.Loading(i + 1, waits.size)
            if (i > 0) guard.edit().putBoolean(RETRY_IN_FLIGHT, true).commit()
            val made = runCatching { create(paths.first, paths.second) }
                .onFailure { Log.i(TAG, "load attempt ${i + 1} failed: ${it.message}") }.getOrNull()
            if (i > 0) guard.edit().putBoolean(RETRY_IN_FLIGHT, false).commit()
            if (made != null) {
                vlm = made
                _status.value = VisionModel.Status.Ready("Intern3.5-VL-2B on the NPU", System.currentTimeMillis() - started)
                return@withLock true
            }
        }
        _status.value = VisionModel.Status.Failed("the NPU would not take the model")
        gate.unloaded()
        false
    }

    override suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String? {
        if (!ensureLoaded()) return null
        val model = vlm ?: return null
        if (!gate.requestLook()) return null
        var file: File? = null
        return try {
            lock.withLock {
                file = writeFrame(frame)
                val user = VlmChatMessage("user", listOf(VlmContent("image", file!!.absolutePath), VlmContent("text", userPrompt)))
                val system = VlmChatMessage("system", listOf(VlmContent("text", systemPrompt)))
                model.reset()
                val template = model.applyChatTemplate(arrayOf(system, user), null, false).getOrThrow()
                val config = model.injectMediaPathsToConfig(arrayOf(user), GenerationConfig(maxTokens = MAX_TOKENS))
                    .apply { samplerConfig = greedy() }
                val reply = StringBuilder()
                var failure: Throwable? = null
                model.generateStreamFlow(template.formattedText, config).collect { r ->
                    when (r) {
                        is LlmStreamResult.Token -> reply.append(r.text)
                        is LlmStreamResult.Error -> failure = r.throwable
                        else -> Unit
                    }
                }
                if (failure != null) { Log.w(TAG, "generation failed", failure); null } else reply.toString().trim().ifBlank { null }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "look failed", t); null
        } finally {
            // A picture of a baby exists on disk for one inference only.
            runCatching { file?.delete() }
            gate.releaseLook()
        }
    }

    private fun greedy() = SamplerConfig(temperature = 0f, topP = 1f, topK = 1, minP = 0f, repetitionPenalty = 1f, presencePenalty = 0f, seed = 42)

    private fun writeFrame(frame: Bitmap): File {
        val dir = File(context.cacheDir, "vision").apply { mkdirs() }
        val f = File(dir, "look.jpg")
        // The encoder takes 448x448; a modest JPEG keeps the write fast.
        val scale = minOf(1f, 896f / maxOf(frame.width, frame.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private suspend fun initSdk(): Boolean = suspendCancellableCoroutine { cont ->
        GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onFailure(reason: String) { Log.w(TAG, "GenieX init failed: $reason"); if (cont.isActive) cont.resume(false) }
        })
    }

    private suspend fun registerBundle(): Pair<String, String?> {
        ModelManagerWrapper.getPaths(MODEL_NAME)?.let { return it.model_path to it.mmproj_path }
        var error: String? = null
        ModelManagerWrapper.pullFlow(
            ModelPullInput(model_name = MODEL_NAME, precision = PRECISION, hub = HubSource.LOCALFS,
                local_path = bundleDir().absolutePath, model_type = ModelType.VLM)
        ).collect { e -> if (e is ModelManagerWrapper.PullEvent.Error) error = "${e.code}: ${e.message}" }
        error?.let { throw IllegalStateException(it) }
        val p = ModelManagerWrapper.getPaths(MODEL_NAME) ?: throw IllegalStateException("no paths after registering")
        return p.model_path to p.mmproj_path
    }

    private suspend fun create(modelPath: String, mmprojPath: String?): VlmWrapper =
        VlmWrapper.builder()
            .vlmCreateInput(VlmCreateInput(model_path = modelPath, mmproj_path = mmprojPath,
                config = ModelConfig(nCtx = 0, nGpuLayers = 0), runtime_id = GenieXSdk.PLUGIN_ID_QAIRT,
                compute_unit = ComputeUnitValue.NPU.value ?: "npu"))
            .dispatcher(worker)
            .build()
            .getOrThrow()

    override fun close() {
        runCatching { vlm?.destroy() }
        vlm = null
        _status.value = if (isInstalled()) VisionModel.Status.Installed else VisionModel.Status.Absent
        gate.unloaded()
    }
}
