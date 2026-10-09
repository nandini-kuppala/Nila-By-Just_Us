package com.nila.assistant

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.SamplerConfig
import com.nila.npu.NpuArbiter
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.coroutines.resume

/**
 * Qwen3.5-2B, Q4_0, on the Hexagon NPU, through Qualcomm's GenieX SDK.
 *
 * ### Why this runtime and not the one already in the app
 *
 * The app's existing bridge reaches MediaPipe (`.task`) and LiteRT-LM
 * (`.litertlm`). MediaPipe's `Backend` enum has CPU and GPU and no NPU at all,
 * so a `.task` model cannot touch the Hexagon block however it is configured.
 * GenieX bundles llama.cpp's Hexagon backend (`libggml-hexagon.so` plus
 * `libggml-htp-v81.so` for this SoC) inside the AAR, and it is the only route
 * measured to open the NPU from inside an ordinary Android process -- a
 * self-built llama.cpp loaded in-process failed the FastRPC capability query
 * with err 114, while the same binaries worked from an `adb shell`.
 *
 * ### Why Q4_0 specifically
 *
 * The quantisation decides whether the NPU is used at all. Same model, same
 * phone, same build: Q4_0 processed a prompt at 760 tok/s against Q4_K_M's
 * 104 -- 7.3x -- because the Hexagon backend can only repack Q4_0, Q4_1, Q8_0,
 * IQ4_NL and MXFP4. Anything else silently lands on CPU.
 *
 * ### Why thinking is off
 *
 * Qwen3.5 can emit a reasoning trace, and on one baby-care question it emitted
 * 3,092 tokens of it. At the ~25 tok/s this phone generates, that is two
 * minutes before the first word of the answer appears. A parent at 2am is the
 * entire design constraint here, so the trace is off and the budget goes to
 * the answer.
 */
class NpuLlm(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "NpuLlm"

        /** The name GenieX registers this model under in its own cache. */
        const val MODEL_NAME = "local/Qwen3.5-2B-GGUF"
        const val PRECISION = "Q4_0"
        const val GGUF_FILE = "Qwen3.5-2B-Q4_0.gguf"

        /**
         * Prompt plus reply, in tokens.
         *
         * The weights are trained to 262,144 and the phone will hold 32,768,
         * but the KV cache is allocated up front and a window costs prefill
         * setup whether it is used or not. Everything this pipeline sends --
         * a system prompt, one or two passages, a dozen fact lines and two
         * previous turns -- fits inside 4,096 with room to spare.
         */
        const val N_CTX = 4096

        /**
         * Sampling. The Unsloth guide's thinking-off preset is 0.7 / 0.8, and
         * that is right for open-ended chat. This is not open-ended chat: the
         * facts are supplied and the job is to say them back accurately. At
         * 0.7 the model invented the connective tissue between two facts it had
         * been handed -- five crying episodes "indicates she is getting plenty
         * of nutrition", a passed hearing screen "confirming that breastfeeding
         * is meeting her sensory needs". Lower temperature costs some fluency
         * and buys back exactly that; 0.4 was better than 0.7 and 0.3 better
         * again, measured on the same forty-one questions each time.
         */
        private const val TEMPERATURE = 0.3f
        private const val TOP_P = 0.8f
        private const val TOP_K = 20
        private const val MIN_P = 0.0f
        private const val PRESENCE_PENALTY = 1.5f
        private const val REPEAT_PENALTY = 1.0f

        /** `ComputeUnitValue.NPU.value`, which the SDK declares as nullable. */
        private val COMPUTE_UNIT: String = ComputeUnitValue.NPU.value ?: "npu"

        /**
         * Where this model goes when the camera has the NPU.
         *
         * There is one Hexagon block and two tenants -- this and the scene model
         * the safety watch asks about the cot -- and they cannot both pin their
         * weights into it. The camera wins, because a parent waiting a little
         * longer for an answer is a worse outcome than a baby reaching for a
         * socket unobserved. See [NpuArbiter] for the whole rule.
         *
         * The cost of losing is real but bounded: on this phone the same model
         * prefills about seven times slower on the CPU and generates about a
         * third slower. An answer still arrives, which is the point.
         */
        private val CPU_UNIT: String = ComputeUnitValue.CPU.value ?: "cpu"

        /** GGUF is read by the llama.cpp plugin; `qairt` reads compiled bundles. */
        private const val RUNTIME = GenieXSdk.PLUGIN_ID_LLAMA_CPP
    }

    /** What the engine is doing, for a status line that can say which wait it is. */
    sealed interface State {
        data object Absent : State
        data object Initialising : State
        data object Registering : State
        data object Loading : State
        data class Ready(val loadMs: Long, val computeUnit: String) : State
        data class Failed(val reason: String) : State
    }

    @Volatile
    var state: State = State.Absent
        private set

    private var llm: LlmWrapper? = null

    /** Which processor this engine was actually built on, for the status line. */
    @Volatile
    var computeUnitInUse: String = COMPUTE_UNIT
        private set

    /**
     * One thread, for the whole engine.
     *
     * Generation reaches a blocking JNI call that a coroutine cancellation
     * cannot interrupt, so it must never share a pool with anything else -- two
     * slow calls on Dispatchers.Default would starve the rest of the app. The
     * SDK takes the dispatcher at build time and uses it for every call after.
     */
    private val worker: ExecutorCoroutineDispatcher =
        java.util.concurrent.Executors
            .newSingleThreadExecutor { r -> Thread(r, "nila-npu-llm") }
            .asCoroutineDispatcher()

    val isReady: Boolean get() = llm != null

    /**
     * Held for every build, every generation and every eviction, so the three
     * can never overlap: the camera taking the NPU must not close an engine that
     * is halfway through a sentence, or halfway through being built.
     */
    private val engineLock = Mutex()

    /**
     * True when the engine was taken off the NPU for the camera and has not been
     * rebuilt yet. The next question rebuilds it, on whichever processor the
     * arbiter then allows -- so Ask keeps answering in its own words while the
     * watch runs, just on the CPU.
     */
    @Volatile
    var evicted: Boolean = false
        private set

    /** Whether a question can be answered by the model, now or after a rebuild. */
    val canAnswer: Boolean get() = llm != null || evicted

    private val evictor: suspend () -> Boolean = { evictFromNpu() }

    init {
        NpuArbiter.registerChatEvictor(evictor)
    }

    /**
     * Get off the NPU for the camera.
     *
     * @return true when this engine is not on the NPU afterwards (or never was),
     *   false when it is busy -- generating or being built -- and must be asked
     *   again at the next look.
     */
    suspend fun evictFromNpu(): Boolean {
        if (!engineLock.tryLock()) return false
        try {
            val current = llm ?: return true
            if (computeUnitInUse != COMPUTE_UNIT) return true
            // On the engine's own thread, after any JNI call already queued there.
            withContext(worker) { runCatching { current.close() } }
            llm = null
            evicted = true
            state = State.Absent
            NpuArbiter.chatComputeUnit(onNpu = false)
            Log.i(TAG, "moved off the NPU for the camera; the next question rebuilds on the CPU")
            return true
        } finally {
            engineLock.unlock()
        }
    }

    /**
     * Where the weights are, if they are anywhere.
     *
     * Two places, in the order they are likely. [ModelInstaller] downloads into
     * the app's external files directory, which is where a user who tapped the
     * button will have them. The internal `local/` directory is the other
     * route -- a file placed there over adb for a demo -- and is checked
     * second so a real download always wins.
     */
    fun modelFile(): File {
        val downloaded = File(context.getExternalFilesDir("models"), GGUF_FILE)
        if (downloaded.exists()) return downloaded
        return File(File(context.filesDir, "local"), GGUF_FILE)
    }

    fun modelPresent(): Boolean = modelFile().let { it.exists() && it.length() > 1_000_000_000 }

    /**
     * Initialise the SDK, register the local GGUF, and load it onto the NPU.
     *
     * Returns true when generation is possible. Every failure is recorded in
     * [state] rather than thrown, because the pipeline above this has a
     * complete answer without a model and must not be interrupted by its
     * absence.
     */
    suspend fun start(onStep: (State) -> Unit = {}): Boolean =
        engineLock.withLock { startLocked(onStep) }

    private suspend fun startLocked(onStep: (State) -> Unit): Boolean {
        if (isReady) return true
        if (!modelPresent()) {
            update(State.Failed("no ${GGUF_FILE} in files/local"), onStep)
            return false
        }

        update(State.Initialising, onStep)
        val initialised = initSdk()
        if (!initialised.isSuccess) {
            update(State.Failed("SDK init: ${initialised.exceptionOrNull()?.message}"), onStep)
            return false
        }

        update(State.Loading, onStep)
        val started = System.currentTimeMillis()

        // Ask the referee before building anything. If the camera lane is in the
        // middle of looking at the cot, this engine goes to the CPU for now and
        // moves back the next time it is rebuilt with the NPU quiet.
        computeUnitInUse = if (NpuArbiter.chatMayUseNpu()) COMPUTE_UNIT else CPU_UNIT
        NpuArbiter.chatComputeUnit(computeUnitInUse == COMPUTE_UNIT)
        Log.i(TAG, "building on $computeUnitInUse")

        // The GGUF is already on this device, in this app's own storage, so
        // hand its path to the runtime and be done. Registering it through
        // ModelManagerWrapper instead makes GenieX copy it into its own cache,
        // which for this file means a second 1.1 GB on a phone that already
        // has the first one. The managed route stays as the fallback, because
        // it is the one Qualcomm document, and if a future SDK stops accepting
        // a bare path this keeps working.
        var wrapper = runCatching { createFrom(modelFile().absolutePath, RUNTIME) }
            .onFailure { Log.w(TAG, "direct load failed, registering instead", it) }
            .getOrNull()

        if (wrapper == null) {
            update(State.Registering, onStep)
            val managed = runCatching { registerThenCreate() }
            wrapper = managed.getOrNull()
            if (wrapper == null) {
                update(State.Failed("load: ${managed.exceptionOrNull()?.message}"), onStep)
                return false
            }
        }
        llm = wrapper
        evicted = false
        update(State.Ready(System.currentTimeMillis() - started, computeUnitInUse), onStep)
        return true
    }

    private fun update(next: State, onStep: (State) -> Unit) {
        state = next
        Log.i(TAG, "state -> $next")
        onStep(next)
    }

    private suspend fun initSdk(): Result<Unit> = suspendCancellableCoroutine { cont ->
        GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Result.success(Unit))
            }

            override fun onFailure(reason: String) {
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(reason)))
            }
        })
    }

    /**
     * Register the GGUF that is already on the device.
     *
     * `local_path` is the *directory* holding the file, not the file. GenieX
     * copies it into its own cache and writes a `geniex.json` beside it; the
     * copy is what [ModelManagerWrapper.getPaths] later resolves.
     */
    private suspend fun register() {
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = MODEL_NAME,
                precision = PRECISION,
                hub = HubSource.LOCALFS,
                local_path = modelFile().parentFile!!.absolutePath,
                model_type = ModelType.LLM,
            )
        ).collect { event ->
            when (event) {
                is ModelManagerWrapper.PullEvent.Error ->
                    throw IllegalStateException("${event.code}: ${event.message}")
                else -> Log.d(TAG, "pull: $event")
            }
        }
    }

    private suspend fun registerThenCreate(): LlmWrapper {
        register()
        val paths = ModelManagerWrapper.getPaths(MODEL_NAME)
            ?: throw IllegalStateException("no paths for $MODEL_NAME after registering")
        Log.i(TAG, "paths: runtime=${paths.runtime_id} model=${paths.model_path}")
        return createFrom(paths.model_path, paths.runtime_id ?: RUNTIME, paths.tokenizer_path)
    }

    private suspend fun createFrom(
        path: String,
        runtime: String,
        tokenizer: String? = null,
    ): LlmWrapper = LlmWrapper.builder()
        .llmCreateInput(
            // GenieX 0.4.0 moved the reply ceiling to GenerationConfig.maxTokens,
            // which generate() sets on every call, and dropped enable_thinking
            // from the model config -- reasoning is switched off where it always
            // also was, in the chat template (applyChatTemplate's `false`).
            LlmCreateInput(
                model_path = path,
                tokenizer_path = tokenizer,
                config = ModelConfig(
                    nCtx = N_CTX,
                    nGpuLayers = 999,
                ),
                runtime_id = runtime,
                compute_unit = computeUnitInUse,
            )
        )
        .dispatcher(worker)
        .build()
        .getOrThrow()

    /** One exchange with the model, and what it cost. */
    data class Generation(
        val text: String,
        val promptTokens: Long = 0,
        val generatedTokens: Long = 0,
        val prefillTokensPerSecond: Double = 0.0,
        val decodeTokensPerSecond: Double = 0.0,
        val ttftMs: Double = 0.0,
        val totalMs: Long = 0,
        val stopReason: String = "",
    )

    /**
     * Generate a reply to [messages], streaming partial text to [onToken].
     *
     * The chat template embedded in the GGUF turns the roles into the prompt
     * the weights expect; passing raw text instead produces a continuation of
     * the question rather than an answer to it.
     */
    suspend fun generate(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        seed: Int = -1,
        onToken: (String) -> Unit = {},
    ): Generation? = engineLock.withLock { generateLocked(messages, maxTokens, seed, onToken) }

    /**
     * The engine to answer with, rebuilding it if the camera moved it.
     *
     * Two cases besides the ordinary one. Evicted: rebuild, and the arbiter will
     * say CPU while the camera's model is on the NPU. Built on the CPU while the
     * camera had the NPU, which it has since given back: rebuild on the NPU,
     * because that is three times the speed for every question after this one.
     */
    private suspend fun activeEngine(): LlmWrapper? {
        val current = llm
        if (current == null) {
            if (!evicted) return null
            return if (startLocked {}) llm else null
        }
        if (computeUnitInUse != COMPUTE_UNIT && NpuArbiter.chatMayUseNpu()) {
            Log.i(TAG, "the NPU is free again; moving back onto it")
            withContext(worker) { runCatching { current.close() } }
            llm = null
            return if (startLocked {}) llm else null
        }
        return current
    }

    private suspend fun generateLocked(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        seed: Int,
        onToken: (String) -> Unit,
    ): Generation? {
        val active = activeEngine() ?: return null
        val started = System.currentTimeMillis()

        val prompt = active.applyChatTemplate(
            messages.map { (role, content) -> ChatMessage(role, content) }.toTypedArray(),
            null,
            false,
        ).getOrNull()?.formattedText ?: return null

        val config = GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(
                temperature = TEMPERATURE,
                topP = TOP_P,
                topK = TOP_K,
                minP = MIN_P,
                repetitionPenalty = REPEAT_PENALTY,
                presencePenalty = PRESENCE_PENALTY,
                seed = seed,
            ),
        )

        val out = StringBuilder()
        var profile: com.geniex.sdk.bean.ProfilingData? = null
        var failure: Throwable? = null

        // Tell the referee a reply is in flight. The camera lane will wait for
        // this rather than interrupt it: a half-written answer disappearing
        // mid-sentence is worse for the reader than a look happening a few
        // seconds later.
        NpuArbiter.chatGenerationStarted()
        try {
            active.generateStreamFlow(prompt, config).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        out.append(result.text)
                        onToken(result.text)
                    }
                    is LlmStreamResult.Completed -> profile = result.profile
                    is LlmStreamResult.Error -> failure = result.throwable
                }
            }
        } finally {
            NpuArbiter.chatGenerationFinished()
        }

        failure?.let {
            Log.w(TAG, "generation failed", it)
            return null
        }

        val p = profile
        return Generation(
            text = out.toString(),
            promptTokens = p?.promptTokens ?: 0,
            generatedTokens = p?.generatedTokens ?: 0,
            prefillTokensPerSecond = p?.prefillSpeed ?: 0.0,
            decodeTokensPerSecond = p?.decodingSpeed ?: 0.0,
            ttftMs = p?.ttftMs ?: 0.0,
            totalMs = System.currentTimeMillis() - started,
            stopReason = p?.stopReason ?: "",
        )
    }

    /** Drop the KV cache between unrelated conversations. */
    suspend fun reset() {
        runCatching { llm?.reset() }
    }

    override fun close() {
        NpuArbiter.unregisterChatEvictor(evictor)
        runCatching { llm?.close() }
        llm = null
        worker.close()
        state = State.Absent
    }
}
