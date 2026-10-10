package com.nila.visit.llm

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Qwen3.5-2B on the Hexagon NPU, the model the main app's Ask already runs.
 *
 * The same GenieX 0.4.0 calls as the app's NpuLlm, copied rather than shared:
 * this lab must not touch the stable app. Two things differ, because the job
 * differs. Sampling is near-greedy with no presence penalty -- the output is a
 * JSON object whose keys repeat by design, and a presence penalty pushes the
 * model to invent new ones. And the engine is reset before every generation,
 * since GenieX 0.4.0 otherwise keeps the KV cache across calls (the bug that
 * broke Ask in 2.5.2).
 */
class VisitLlm(private val context: Context, private val modelFile: File) : Closeable {

    companion object {
        private const val TAG = "VisitLlm"
        const val N_CTX = 4096
    }

    data class Generation(
        val text: String,
        val promptTokens: Long,
        val generatedTokens: Long,
        val prefillTps: Double,
        val decodeTps: Double,
        val totalMs: Long,
        val loadMs: Long,
    )

    private val worker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "visit-llm") }.asCoroutineDispatcher()
    private var llm: LlmWrapper? = null
    var loadMs: Long = 0; private set
    val isLoaded: Boolean get() = llm != null

    suspend fun load() {
        if (llm != null) return
        require(modelFile.length() > 1_000_000_000) { "no model at $modelFile" }
        val started = System.currentTimeMillis()
        suspendCancellableCoroutine { cont ->
            GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(reason: String) {
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(reason)))
                }
            })
        }
        llm = LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_path = modelFile.absolutePath,
                    tokenizer_path = null,
                    config = ModelConfig(nCtx = N_CTX, nGpuLayers = 999),
                    runtime_id = GenieXSdk.PLUGIN_ID_LLAMA_CPP,
                    compute_unit = ComputeUnitValue.NPU.value ?: "npu",
                )
            )
            .dispatcher(worker)
            .build()
            .getOrThrow()
        loadMs = System.currentTimeMillis() - started
        Log.i(TAG, "loaded on the NPU in ${loadMs}ms")
    }

    suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}): Generation {
        load()
        val active = llm!!
        runCatching { active.reset() }
        val started = System.currentTimeMillis()
        val config = GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(
                temperature = 0.1f, topP = 0.9f, topK = 1, minP = 0f,
                repetitionPenalty = 1.0f, presencePenalty = 0f, seed = 7,
            ),
        )
        val out = StringBuilder()
        var profile: com.geniex.sdk.bean.ProfilingData? = null
        var failure: Throwable? = null
        active.generateStreamFlow(prompt, config).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> { out.append(r.text); onToken(r.text) }
                is LlmStreamResult.Completed -> profile = r.profile
                is LlmStreamResult.Error -> failure = r.throwable
            }
        }
        failure?.let { throw it }
        val p = profile
        return Generation(
            text = out.toString(),
            promptTokens = p?.promptTokens ?: 0, generatedTokens = p?.generatedTokens ?: 0,
            prefillTps = p?.prefillSpeed ?: 0.0, decodeTps = p?.decodingSpeed ?: 0.0,
            totalMs = System.currentTimeMillis() - started, loadMs = loadMs,
        )
    }

    /** Give the NPU back: the next stage, or the next visit's Whisper, needs it. */
    override fun close() {
        runCatching { llm?.close() }
        llm = null
    }
}
