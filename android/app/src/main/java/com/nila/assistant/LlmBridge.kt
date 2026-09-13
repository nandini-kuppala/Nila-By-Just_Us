package com.nila.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.Closeable
import java.io.File

/**
 * A small local model, used to *phrase* an answer retrieval has already found.
 *
 * It supplies no facts. Retrieval picks the passages; this turns them into two
 * or three sentences that answer the actual question, instead of two paragraphs
 * of reference text a parent has to read twice at 3am.
 *
 * ### What it is not allowed to see
 *
 * This was live once and was removed after it invented a failed newborn hearing
 * screening. It had been handed the mother's own health records as context,
 * found one saying the screening was *passed*, and inverted it.
 *
 * So the prompt built here contains exactly two things: the retrieved passages
 * and the question. No health records, no care log, no baby profile. A model
 * that never sees a record cannot invert one, and that is a stronger guarantee
 * than any amount of prompt instruction.
 *
 * ### What is checked afterwards
 *
 * Prompt discipline is necessary and not sufficient, so [isGrounded] rejects
 * output that invents a quantity, drifts off the source vocabulary, or -- the
 * failure that mattered -- flips the polarity of a sentence it is paraphrasing.
 * A rejected rewrite costs nothing: the caller keeps the retrieved text, which
 * was always a complete, sourced answer.
 */
class LlmBridge private constructor(
    private val context: Context,
    private val modelPath: String?,
) : Closeable {

    companion object {
        private const val TAG = "LlmBridge"

        /** Set while a GPU generation is in flight; cleared when it returns. */
        private const val GPU_IN_FLIGHT = "gpu_in_flight"

        /** Set once a GPU generation has been seen not to return. */
        private const val GPU_UNSAFE = "gpu_unsafe"

        /**
         * Weights this app will answer with, and the runtime each one needs.
         *
         * Two formats, because they reach different silicon. MediaPipe reads
         * `.task` and offers CPU and GPU only -- there is no NPU in its
         * `Backend` enum, so a `.task` model cannot touch the Hexagon block
         * whatever else is done. LiteRT-LM reads `.litertlm` and its `Backend`
         * does have `NPU`, which is the only route to it that this app has.
         *
         * So a `.litertlm` is preferred whenever one is present, and the
         * `.task` file remains as the fallback that is known to work.
         */
        private val LITERT_CANDIDATES = listOf(
            "gemma-3n-E2B-it.litertlm",
            "gemma3-1b-it.litertlm",
            "qwen2.5-0.5b-instruct.litertlm",
        )

        private val TASK_CANDIDATES = listOf(
            "qwen2.5-0.5b-instruct.task",
            "gemma3-1b-it.task",
        )

        private val CANDIDATES = LITERT_CANDIDATES + TASK_CANDIDATES

        /**
         * Prompt plus answer, in tokens. Fixed by the weights, not by taste.
         *
         * The shipped file is `..._ekv1280.task`, and that suffix is the size
         * of the KV cache it was built with. Ask for more and the GPU backend
         * refuses to initialise at all:
         *
         *     Max number of tokens is larger than the maximum cache size
         *     supported: 2048 > 1280
         *
         * The CPU backend accepts the same number without complaint, which is
         * how a value that was too large looked fine for as long as nothing
         * tried to accelerate it -- the app quietly fell back and got slower.
         *
         * Overrunning the window at generation time is worse than either:
         * `nativePredictSync` dereferences null and takes the process with it,
         * which is what three passages and ten fact lines did the first time
         * this prompt ran on a phone. [PROMPT_BUDGET_CHARS] is what keeps the
         * input far enough below this for that to be unreachable.
         */
        private const val MAX_TOKENS = 1280

        /**
         * Characters of prompt, which is the number that actually has to hold.
         *
         * Roughly four characters to the token, so this leaves around half
         * the window for the answer. Counted in characters because
         * that is what can be measured here without a tokeniser, and erring
         * low costs a sentence of context where erring high costs the process.
         */
        private const val PROMPT_BUDGET_CHARS = 1500

        /**
         * No single passage may take more than this much of the budget.
         *
         * Prefill cost is close to linear in the prompt, and on a CPU that is
         * seconds rather than milliseconds. Retrieval ranks the passages, so
         * the top one carries most of the answer and the second mostly costs
         * time -- hence one passage, not two, and a tight clamp on it.
         */
        private const val PASSAGE_CHARS = 520

        /** Facts are one line each; a long one is a filed record's extract. */
        private const val FACT_CHARS = 110

        /** A generation that runs past this is stopped and used as it stands. */
        private const val GENERATE_TIMEOUT_MS = 20_000L

        /** How long to let the runtime acknowledge a cancel before closing. */
        private const val CANCEL_GRACE_MS = 4_000L

        /** The answer is a rephrasing, not an essay. */
        private const val MAX_WORDS = 70

        fun tryLoad(context: Context): LlmBridge {
            val dir = context.getExternalFilesDir("models")
            fun pick(names: List<String>) = names
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.length() > 1_000_000 }
            // A LiteRT-LM model first: it is the only one that can reach the NPU.
            val found = pick(LITERT_CANDIDATES) ?: pick(TASK_CANDIDATES)
            return LlmBridge(context.applicationContext, found?.absolutePath)
        }

        /** True when a model file is present, whether or not it has loaded yet. */
        fun isInstalled(context: Context): Boolean {
            val dir = context.getExternalFilesDir("models")
            return CANDIDATES.any {
                File(dir, it).let { f -> f.exists() && f.length() > 1_000_000 }
            }
        }
    }

    /**
     * A note left on disk while the GPU is mid-generation.
     *
     * The MediaPipe GPU backend loads this model happily and then dereferences
     * null inside `nativePredictSync`, which is a SIGSEGV: no exception, no
     * catch block, the process is simply gone. The same prompt on the CPU
     * backend answers in three seconds, so it is the backend and not the input.
     *
     * Nothing in-process can guard against that, so the guard is out of
     * process. The flag is committed *synchronously* before the call and
     * cleared after it, which means finding it set at startup can only mean
     * one thing: the last attempt did not return. The backend is written off
     * permanently at that point and every later load goes straight to the CPU.
     *
     * The cost of a GPU that turns out to be unusable is therefore one crash,
     * once, ever -- and on hardware where it works, nothing at all.
     */
    private val watchdog = context.getSharedPreferences("llm_backend", Context.MODE_PRIVATE)

    private val isLiteRt = modelPath?.endsWith(".litertlm") == true

    @Volatile private var litert: com.google.ai.edge.litertlm.Engine? = null

    @Volatile private var litertBackend: com.google.ai.edge.litertlm.Backend? = null

    @Volatile private var engine: LlmInference? = null

    /** Which backend accepted the weights, for the screen that reports it. */
    @Volatile
    var active: LlmInference.Backend? = null
        private set
    @Volatile private var failure: String? = null
    @Volatile private var attempted = false

    val isReady: Boolean get() = engine != null || litert != null

    fun describe(): String = when {
        modelPath == null -> "Not installed - answers are retrieved text only"
        litert != null ->
            "${File(modelPath).name}, answering on this phone" +
                (litertBackend?.let { " (LiteRT-LM, ${it.name})" } ?: "")
        engine != null ->
            "${File(modelPath).name}, answering on this phone" +
                (active?.let { " (MediaPipe, ${it.name})" } ?: "")
        failure != null -> "Failed to load: $failure"
        else -> "Installed, not loaded yet"
    }

    /** Which compute unit is answering, for the screen that reports it. */
    val backendName: String?
        get() = litertBackend?.name ?: active?.name

    /**
     * Build the engine. Blocking and slow -- maps the weights and builds an
     * XNNPack cache -- so callers keep it off any thread that matters.
     */
    @Synchronized
    fun load(): Boolean {
        if (engine != null) return true
        if (attempted && failure != null) return false
        attempted = true
        val path = modelPath ?: return false

        if (isLiteRt) return loadLiteRt(path)

        // GPU first, CPU only if it will not start -- or if it has already
        // proved it cannot generate on this device.
        //
        // MediaPipe's LLM runtime exposes two backends and defaults to the
        // CPU, which for a half-billion-parameter model is the difference
        // between an answer and a wait somebody gives up on. There is no NPU
        // backend in this API: the Hexagon block is reachable for the TFLite
        // graphs through NNAPI, and not for this one.
        val gpuWrittenOff = watchdog.getBoolean(GPU_UNSAFE, false) ||
            watchdog.getBoolean(GPU_IN_FLIGHT, false)
        if (gpuWrittenOff && !watchdog.getBoolean(GPU_UNSAFE, false)) {
            // Set but never cleared: the last generation did not return.
            watchdog.edit().putBoolean(GPU_UNSAFE, true).commit()
            Log.w(TAG, "GPU generation crashed previously; CPU from now on")
        }
        val order = if (gpuWrittenOff) {
            listOf(LlmInference.Backend.CPU)
        } else {
            listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
        }

        for (backend in order) {
            val started = System.currentTimeMillis()
            try {
                engine = LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(MAX_TOKENS)
                        .setMaxTopK(40)
                        .setPreferredBackend(backend)
                        .build(),
                )
                active = backend
                failure = null
                Log.i(TAG, "loaded on $backend in ${System.currentTimeMillis() - started}ms")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "$backend load failed", t)
                failure = t.message ?: t::class.java.simpleName
                engine = null
            }
        }
        Log.e(TAG, "no backend would load the model")
        return false
    }

    /**
     * Phrase [passages] as an answer to [question], or return null.
     *
     * Null on every failure path -- no model, generation error, timeout, or a
     * rewrite that failed grounding -- and the caller then shows the retrieved
     * text unchanged.
     */
    fun phrase(question: String, passages: List<String>, language: String): String? {
        val active = engine ?: return null
        if (passages.isEmpty()) return null

        val source = passages.joinToString("\n")
        val started = System.currentTimeMillis()
        val raw = try {
            LlmInferenceSession.createFromOptions(
                active,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    // Low temperature: this is a rephrasing task, and sampling
                    // creatively from a 0.5B model on health text is exactly
                    // how it starts inventing.
                    .setTemperature(0.1f)
                    .setTopK(20)
                    .build(),
            ).use { session ->
                session.addQueryChunk(buildPrompt(question, passages, language))
                session.generateResponse()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "generation failed", t)
            return null
        }

        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "generated ${raw.length} chars in ${elapsed}ms")

        val cleaned = sanitise(raw) ?: run {
            Log.w(TAG, "rejected: unusable shape")
            return null
        }
        // A small model asked to shorten something often hands it straight
        // back. That is harmless but pointless, and swapping identical text in
        // under the reader makes the screen flicker for nothing.
        if (cleaned.length > source.length * 0.85) {
            Log.i(TAG, "rejected: no shorter than the source")
            return null
        }
        if (!Grounding.isGrounded(cleaned, source)) return null
        Log.i(TAG, "phrased ${source.length} chars down to ${cleaned.length}")
        return cleaned
    }

    /**
     * Load through LiteRT-LM, trying the NPU first.
     *
     * This is the only path in the app that can put a language model on the
     * Hexagon block. MediaPipe's `Backend` enum has two entries, CPU and GPU,
     * so a `.task` model is barred from the NPU by the runtime rather than by
     * the hardware; LiteRT-LM's has three.
     *
     * The ladder is NPU, then GPU, then CPU, and each rung is *proved* by
     * building the engine rather than assumed from a capability list -- the
     * same rule [com.nila.ml.TfliteRunner] holds its delegates to, and for the
     * same reason: a backend that constructs can still fail, and an NPU build
     * that quietly fell back to the CPU while reporting success is exactly the
     * kind of claim this project does not want to be making.
     *
     * NPU support also depends on the *weights*: a `.litertlm` has to carry
     * an NPU-compiled section for the accelerator to take it. Where it does
     * not, this falls through and says which rung it landed on.
     */
    private fun loadLiteRt(path: String): Boolean {
        val gpuWrittenOff = watchdog.getBoolean(GPU_UNSAFE, false)
        val ladder = buildList {
            add(com.google.ai.edge.litertlm.Backend.NPU)
            if (!gpuWrittenOff) add(com.google.ai.edge.litertlm.Backend.GPU)
            add(com.google.ai.edge.litertlm.Backend.CPU)
        }

        for (backend in ladder) {
            val started = System.currentTimeMillis()
            try {
                val built = com.google.ai.edge.litertlm.Engine(
                    com.google.ai.edge.litertlm.EngineConfig(
                        modelPath = path,
                        backend = backend,
                        // Text only. Naming a vision or audio backend makes
                        // LiteRT-LM look for encoder sections a text model
                        // does not carry, and the engine then fails to build
                        // at all -- see the same note in SceneDescriber.
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                ).also { it.initialize() }
                litert = built
                litertBackend = backend
                failure = null
                Log.i(TAG, "LiteRT-LM loaded on $backend in " +
                    "${System.currentTimeMillis() - started}ms")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "LiteRT-LM $backend unavailable: ${t.message}")
                failure = t.message ?: t::class.java.simpleName
                litert = null
                litertBackend = null
            }
        }
        Log.e(TAG, "no LiteRT-LM backend would load the model")
        return false
    }

    /**
     * Generate through LiteRT-LM, stopping as soon as the answer is complete.
     *
     * Same early stop as the MediaPipe path and for the same reason -- a small
     * model asked for three sentences keeps going -- expressed against this
     * runtime's own streaming callback and `cancelProcess`.
     */
    private fun generateLiteRt(prompt: String): String? {
        val active = litert ?: return null
        val text = StringBuilder()
        val finished = java.util.concurrent.CountDownLatch(1)
        val stoppedEarly = java.util.concurrent.atomic.AtomicBoolean(false)

        return try {
            active.createSession(com.google.ai.edge.litertlm.SessionConfig()).use { session ->
                session.generateContentStream(
                    listOf(com.google.ai.edge.litertlm.InputData.Text(prompt)),
                    object : com.google.ai.edge.litertlm.ResponseCallback {
                        override fun onNext(response: String) {
                            val enough = synchronized(text) {
                                text.append(response)
                                hasSaidEnough(text)
                            }
                            if (enough) {
                                stoppedEarly.set(true)
                                finished.countDown()
                            }
                        }

                        override fun onDone() = finished.countDown()

                        override fun onError(error: Throwable) {
                            Log.w(TAG, "LiteRT-LM stream failed", error)
                            finished.countDown()
                        }
                    },
                )
                if (!finished.await(GENERATE_TIMEOUT_MS,
                                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "LiteRT-LM overran ${GENERATE_TIMEOUT_MS}ms")
                    stoppedEarly.set(true)
                }
                if (stoppedEarly.get()) {
                    Log.i(TAG, "stopped early at " +
                        "${synchronized(text) { text.length }} chars")
                    runCatching { session.cancelProcess() }
                        .onFailure { Log.w(TAG, "cancel failed", it) }
                }
                synchronized(text) { text.toString() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LiteRT-LM generation failed", t)
            null
        }
    }

    /**
     * Write the answer, given what retrieval found and who is asking.
     *
     * This is the path the Ask screen uses now. [phrase] shortened a corpus
     * answer the reader was already looking at; this one produces the answer
     * they see, for the two cases where the corpus alone cannot:
     *
     * - **Personal.** The corpus knows what a penicillin allergy means; only
     *   the profile knows she has one. The passages and the facts go in
     *   together and the model joins them.
     * - **Nothing retrieved.** The old behaviour here was to say "I don't have
     *   anything reliable on that offline", which is true about the corpus and
     *   useless to somebody holding a baby at 2am. With no passages the model
     *   answers from general knowledge and its own instructions, and the
     *   caller labels the answer as exactly that.
     *
     * @param facts profile, log and record lines. May be empty.
     * @param passages corpus text. Empty for the general-knowledge path.
     * @return the answer, or null on any failure -- no model, generation
     *   error, unusable shape, or a rewrite that failed [Grounding].
     */
    fun answer(
        question: String,
        passages: List<String>,
        facts: List<String>,
        language: String,
    ): String? {
        if (engine == null && litert == null) return null
        val started = System.currentTimeMillis()
        // Fit the prompt to the window before it is sent.
        //
        // This is not tidiness. A prompt past the token ceiling does not raise
        // -- `nativePredictSync` dereferences null and kills the process, which
        // is exactly what three corpus passages plus ten fact lines did the
        // first time this ran on a phone. Each part is clamped, and if the
        // whole is still too long the *last* passage goes first: retrieval
        // ranks them, so the weakest one is the cheapest thing to lose.
        var used = passages.take(1).map { it.trim().take(PASSAGE_CHARS) }
        val shortFacts = facts.take(6).map { it.trim().take(FACT_CHARS) }
        var prompt = answerPrompt(question, used, shortFacts, language)
        while (prompt.length > PROMPT_BUDGET_CHARS && used.isNotEmpty()) {
            used = used.dropLast(1)
            prompt = answerPrompt(question, used, shortFacts, language)
        }
        Log.i(TAG, "prompt ${prompt.length} chars, ${used.size} passages, " +
            "${shortFacts.size} facts")
        val onGpu = this.active == LlmInference.Backend.GPU
        // Committed, not applied: this has to be on disk before the call that
        // may not return. `apply()` is asynchronous and would lose the race
        // with a SIGSEGV, which is the entire point of writing it.
        if (onGpu) watchdog.edit().putBoolean(GPU_IN_FLIGHT, true).commit()
        val raw = try {
            if (litert != null) {
                generateLiteRt(prompt) ?: return null
            } else {
                generateMediaPipe(prompt)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "generation failed", t)
            return null
        } finally {
            if (onGpu) watchdog.edit().putBoolean(GPU_IN_FLIGHT, false).commit()
        }
        Log.i(TAG, "answered in ${System.currentTimeMillis() - started}ms")

        val cleaned = sanitise(raw) ?: run {
            Log.w(TAG, "rejected: unusable shape")
            return null
        }

        // What the answer is checked against is everything it was allowed to
        // see. With passages that is the corpus plus the facts, so a claim
        // about her own record is held to the record -- which is the check
        // that was missing when this last inverted a hearing screening. With
        // no passages there is nothing to be grounded *in*, so only the
        // quantity rule applies: an invented dose is the one failure that is
        // dangerous whatever the source.
        val source = (used + shortFacts).joinToString("\n")
        val ok = if (passages.isEmpty()) {
            Grounding.inventsNoQuantities(cleaned, source) &&
                Grounding.reassuresNobody(cleaned, facts)
        } else {
            Grounding.isGrounded(cleaned, source)
        }
        if (!ok) return null
        return cleaned
    }

    /**
     * Generate, and stop the moment the answer is complete.
     *
     * The reason this is not `generateResponse()`.
     *
     * That call returns when the model decides it has finished, and a 0.5B
     * model asked for three sentences does not reliably decide anything of the
     * sort: one measured run spent **27 seconds** on a CPU producing a wall of
     * text that [sanitise] then threw away for being too long. The reader waited
     * half a minute to be shown the retrieved passages they would have seen
     * immediately.
     *
     * Streaming turns that into a question of when to stop reading. Three
     * finished sentences past a sensible minimum is an answer; everything after
     * it is the model still talking. Cancelling there is the difference between
     * a few seconds and half a minute, and it costs nothing -- the tail was
     * being discarded anyway.
     *
     * The cancel is best-effort and wrapped: it is the newest call in the
     * narrowest-tested part of this library, and a generation that overruns is
     * a slow answer where a throw here would be no answer at all.
     */
    private fun generateMediaPipe(prompt: String): String {
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(0.1f)
                .setTopK(20)
                .build(),
        )
        try {
            session.addQueryChunk(prompt)
            Log.i(TAG, "prompt is ${session.sizeInTokens(prompt)} tokens")

            val text = StringBuilder()
            // Two latches, because "the answer is complete" and "the runtime
            // has stopped" are different moments and both matter. The first
            // releases the reader; the second is what makes the session safe
            // to close.
            val enoughSaid = java.util.concurrent.CountDownLatch(1)
            val runtimeDone = java.util.concurrent.CountDownLatch(1)
            val stoppedEarly = java.util.concurrent.atomic.AtomicBoolean(false)

            session.generateResponseAsync { partial, done ->
                val enough = synchronized(text) {
                    text.append(partial)
                    hasSaidEnough(text)
                }
                if (done) {
                    runtimeDone.countDown()
                    enoughSaid.countDown()
                } else if (enough) {
                    stoppedEarly.set(true)
                    enoughSaid.countDown()
                }
            }

            if (!enoughSaid.await(GENERATE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "generation overran ${GENERATE_TIMEOUT_MS}ms; taking what there is")
                stoppedEarly.set(true)
            }

            if (stoppedEarly.get()) {
                Log.i(TAG, "stopped early at ${synchronized(text) { text.length }} chars")
                runCatching { session.cancelGenerateResponseAsync() }
                    .onFailure { Log.w(TAG, "cancel failed", it) }
                // Closing while the runtime is still decoding throws
                // "Previous invocation still processing" -- and that throw used
                // to escape the `use` block and discard an answer that was
                // already complete. Waiting for the callback to say it has
                // stopped is what makes the close legal.
                if (!runtimeDone.await(CANCEL_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "runtime did not acknowledge the cancel in ${CANCEL_GRACE_MS}ms")
                }
            }
            return synchronized(text) { text.toString() }
        } finally {
            // Never allowed to lose the answer. A session that refuses to
            // close is a leak worth one log line; a throw here would be a
            // reader who waited and got nothing.
            runCatching { session.close() }
                .onFailure { Log.w(TAG, "session close failed", it) }
        }
    }

    /**
     * Has it answered?
     *
     * Three sentences that actually end, past a floor that stops "Yes." from
     * counting as three. Deliberately generous about what a sentence is: the
     * cost of stopping one sentence late is a second, and the cost of stopping
     * one early is a truncated answer.
     */
    private fun hasSaidEnough(text: CharSequence): Boolean {
        if (text.length < 140) return false
        val sentences = text.count { it == '.' || it == '!' || it == '?' }
        return sentences >= 3 || text.length > MAX_WORDS * 8
    }

    /**
     * The answering prompt.
     *
     * Four blocks in a fixed order -- who is asking, what is known about them,
     * what the corpus says, what was asked -- and then the rules. A 0.5B model
     * follows a template far better than it follows a paragraph, and it uses
     * the last thing it read most heavily, which is why the question sits
     * directly above the rules and the rules directly above the answer.
     *
     * The facts are labelled ABOUT THIS FAMILY and the corpus NOTES, separately
     * and always in that order, because the two have different standing: the
     * facts are true of this person and the notes are true in general. Asking
     * the model to keep them apart is the only way the answer can say "your
     * records list a penicillin allergy, so not this one" rather than merging
     * the two into a claim about neither.
     */
    private fun answerPrompt(
        question: String,
        passages: List<String>,
        facts: List<String>,
        language: String,
    ): String = buildString {
        appendLine("You are a calm, plain-spoken assistant for a parent of a young baby.")
        appendLine("Answer the QUESTION below. Be brief and practical.")
        appendLine()

        if (facts.isNotEmpty()) {
            appendLine("ABOUT THIS FAMILY (true of this mother and baby):")
            facts.forEach { appendLine("- $it") }
            appendLine()
        }

        if (passages.isNotEmpty()) {
            appendLine("NOTES (reference material, true in general):")
            passages.forEachIndexed { i, passage ->
                appendLine("${i + 1}. $passage")
            }
            appendLine()
        }

        appendLine("QUESTION: ${question.trim()}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Answer in two to four short sentences. Plain words.")
        if (passages.isNotEmpty()) {
            appendLine("- Base the answer on the NOTES. Do not contradict them.")
            appendLine("- Where ABOUT THIS FAMILY changes the answer, say so and why.")
        } else {
            appendLine("- Say only what is widely agreed about infant care.")
            appendLine("- If you are not sure, say so and suggest asking a doctor.")
        }
        appendLine("- Never state a dose, a diagnosis, or a number that is not given above.")
        appendLine("- Never contradict anything under ABOUT THIS FAMILY.")
        appendLine("- Speak to the parent directly. No greeting, no sign-off, no follow-up question.")
        appendLine("- Plain sentences only. No markdown, no bullet points, no headings.")
        if (language != "en") {
            appendLine("- Reply in the language with code '$language'.")
        }
        appendLine()
        append("ANSWER:")
    }

    /**
     * The prompt.
     *
     * Written as notes-plus-question rather than a chat turn, because a 0.5B
     * instruct model handed a conversational frame starts having a conversation
     * -- greeting, offering follow-ups, asking how the baby is doing. The
     * constraints are stated as prohibitions with reasons, which these models
     * follow noticeably better than bare rules.
     */
    private fun buildPrompt(
        question: String,
        passages: List<String>,
        language: String,
    ): String = buildString {
        appendLine("Answer a parent's question using only the notes below.")
        appendLine()
        appendLine("NOTES:")
        passages.take(2).forEachIndexed { i, p ->
            appendLine("${i + 1}. ${p.trim()}")
        }
        appendLine()
        appendLine("QUESTION: ${question.trim()}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Answer the QUESTION in ONE or TWO sentences. Never more.")
        appendLine("- Say only what the NOTES say. Add nothing of your own.")
        appendLine("- Never state a number, dose, age or time that is not in the NOTES.")
        appendLine("- Never negate something the NOTES state, or state something the NOTES negate.")
        appendLine("- Do not copy the NOTES. Answer the question the parent asked.")
        appendLine("- No lists, no headings, no greeting, no sign-off.")
        if (language != "en") {
            appendLine("- Reply in the language with code '$language'.")
        }
        appendLine()
        append("ANSWER:")
    }

    /**
     * Strip the scaffolding these models add around an answer.
     */
    /**
     * Strip the scaffolding these models add around an answer.
     *
     * The shaping itself lives in [ModelText] so it can be tested without a
     * phone or half a gigabyte of weights; what stays here is the decision to
     * reject, which needs [MAX_WORDS].
     */
    private fun sanitise(raw: String): String? {
        val text = ModelText.tidy(raw)
        if (text.length < 25) return null
        if (text.split(Regex("\\s+")).size > MAX_WORDS * 2) return null
        // An assistant refusal is not an answer. "I'm sorry, but I can't
        // assist with that" is worse than the line this app already has for
        // having nothing to say, which at least tells the reader what to do
        // next -- so the refusal is dropped and that line stands instead.
        if (ModelText.isRefusal(text)) {
            Log.i(TAG, "rejected: the model refused rather than answered")
            return null
        }
        return text
    }

    override fun close() {
        runCatching { engine?.close() }
        runCatching { litert?.close() }
        engine = null
        litert = null
        active = null
        litertBackend = null
    }
}

/**
 * Whether a rewrite says only what its source said.
 *
 * Lifted out of [LlmBridge] because it is pure, and because it is the part
 * that has to be testable without a phone or a half-gigabyte model. Everything
 * it rejects, it logs a reason for.
 */
object Grounding {

    private const val TAG = "Grounding"

    /**
     * Does the rewrite say only what the source said?
     */
    private val QUANTITY = Regex(
        """\b\d+(?:\.\d+)?\s?(?:mg|ml|mcg|g|kg|hours?|hrs?|days?|weeks?|months?|years?|times?|%)\b"""
    )

    /**
     * No number-with-a-unit that is not in the source.
     *
     * The most dangerous single thing this can produce, and the only check that
     * still applies when there is no corpus passage to be grounded in -- a
     * general-knowledge answer may range over what it knows, and may not invent
     * a dose while doing it.
     */
    fun inventsNoQuantities(generated: String, source: String): Boolean {
        val src = source.lowercase().replace(" ", "")
        val invented = QUANTITY.findAll(generated.lowercase())
            .map { it.value.replace(" ", "") }
            .filter { !src.contains(it) }
            .toList()
        if (invented.isNotEmpty()) {
            Log.w(TAG, "rejected: invented quantities $invented")
            return false
        }
        return true
    }

    /**
     * Conditions named in the facts, as words worth watching for.
     *
     * Pulled out of the fact lines rather than listed, so it covers whatever
     * this particular mother actually has on file.
     */
    private fun conditionWords(facts: List<String>): Set<String> = facts
        .filter {
            val l = it.lowercase()
            "condition" in l || "allerg" in l || "takes" in l || "notes" in l
        }
        .flatMap { contentWords(it) }
        .toSet()

    private val REASSURANCE = listOf(
        "is not a problem", "are not a problem", "is not a concern",
        "is not an issue", "are not an issue", "no need to worry",
        "nothing to worry", "don't need to worry", "do not need to worry",
        "you are fine", "you're fine", "is perfectly safe", "is safe for you",
        "will not affect", "won't affect", "does not affect", "poses no risk",
        "is not experiencing any", "has no health issues",
        "there is no risk", "no cause for concern",
    )

    /**
     * Refuse an all-clear the model has no standing to give.
     *
     * This exists because of what came back the first time the ungrounded path
     * ran on a phone. Asked "is my asthma a problem while breastfeeding", with
     * her own profile in the prompt and nothing retrieved behind it, a 0.5B
     * model replied:
     *
     *   "In this case, your baby is not experiencing any health issues, and
     *    your asthma is not a problem while breastfeeding."
     *
     * Fluent, confident, and invented. It is the same failure that took
     * generation out of this app once before, in a new place: with no passages
     * there is nothing to be grounded in, so [isGrounded] cannot see it and
     * [inventsNoQuantities] has no number to object to.
     *
     * A model with no source may describe what is generally known. It may not
     * clear a named condition belonging to the person asking. When it tries,
     * the answer is dropped and the caller falls back to saying it has nothing
     * -- which is the honest thing it was trying to avoid saying.
     */
    fun reassuresNobody(generated: String, facts: List<String>): Boolean {
        val lower = generated.lowercase()
        val phrase = REASSURANCE.firstOrNull { it in lower } ?: return true
        // A general reassurance with nothing personal attached is ordinary
        // language. It is the combination -- an all-clear naming something off
        // her own record -- that this refuses.
        val conditions = conditionWords(facts)
        val named = conditions.firstOrNull { it in lower }
        if (named == null && "your" !in lower && "you " !in lower) return true
        Log.w(TAG, "rejected: unfounded reassurance (\"$phrase\"" +
            (named?.let { ", about '$it'" } ?: "") + ")")
        return false
    }

    /**
     * What fraction of the answer's content words come from the source.
     *
     * Exposed so a caller can record it. The floor that rejects an answer is a
     * number about a particular model writing for a particular purpose, and a
     * number like that has to be set from a measured distribution rather than
     * inherited from the model it was set for.
     */
    fun overlap(generated: String, source: String): Double {
        val out = contentWords(generated)
        if (out.isEmpty()) return 0.0
        return out.count { it in contentWords(source) }.toDouble() / out.size
    }

    /**
     * @param source everything the answer was allowed to draw on, used for the
     *   quantity and vocabulary checks.
     * @param claims the *prose* among that -- retrieved passages and the text of
     *   filed records. Only prose is compared for polarity, because polarity is
     *   a property of a proposition and a one-line fact is not one. Matching a
     *   sentence against "Meera is 4 months old" found an overlap on the name
     *   and a "not" somewhere else in the sentence, and rejected two correct
     *   answers for inverting a fact that has nothing to invert.
     */
    fun isGrounded(
        generated: String,
        source: String,
        floor: Double = 0.5,
        claims: String = source,
    ): Boolean {
        // 1. Invented quantities.
        if (!inventsNoQuantities(generated, source)) return false

        // 2. Vocabulary drift.
        val out = contentWords(generated)
        if (out.isEmpty()) return false
        val overlap = overlap(generated, source)
        if (overlap < floor) {
            Log.w(TAG, "rejected: ${(overlap * 100).toInt()}% of content words " +
                "come from the source")
            return false
        }

        // 3. Polarity. The failure that took this feature out of the app was a
        //    sentence built entirely from source words that said the opposite
        //    of the source. Vocabulary overlap is blind to it by construction,
        //    so each piece of the output is matched to the piece of the source
        //    it most resembles and the two are required to agree about negation.
        //
        //    Matched by *clause*, not by sentence. A bigger model merges two
        //    source sentences into one, and a sentence-level match then reads
        //    the negation from the wrong half of it:
        //
        //      "While there is no specific list of foods you must avoid, it is
        //       important to drink plenty of fluids and consume roughly 300 to
        //       500 extra calories a day"
        //
        //    matched "You need roughly 300-500 extra calories a day", found a
        //    "no" in the other clause, and called it an inversion. Split at the
        //    comma and each half matches the source sentence it is actually a
        //    rewrite of, and both agree. The inversion this check exists for --
        //    "the baby has not passed the newborn hearing screening" against
        //    "newborn hearing screening was passed" -- is still caught, because
        //    that one is a whole clause saying the opposite.
        if (claims.isBlank()) return true
        for (clause in clauses(generated)) {
            val words = contentWords(clause)
            if (words.size < 4) continue
            val best = clauses(claims).maxByOrNull { overlapOf(words, it) } ?: continue
            if (overlapOf(words, best) >= 0.6 &&
                isNegated(clause) != isNegated(best)
            ) {
                Log.w(TAG, "rejected: polarity flip on '${clause.take(60)}'")
                return false
            }
        }
        return true
    }

    private val stop = setOf(
        "the", "and", "for", "you", "your", "that", "this", "with", "are", "was",
        "were", "have", "has", "had", "can", "but", "she", "her", "his", "him",
        "they", "them", "there", "then", "from", "into", "about", "would",
        "could", "should", "very", "will", "what", "when", "which", "their",
    )

    private fun contentWords(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z]+"))
        .filter { it.length > 3 && it !in stop }
        .toSet()

    /**
     * How much two sentences say the same thing, as a fraction of the shorter.
     *
     * Measuring against the output alone was the bug: the invented sentence
     * "the baby has not passed the newborn hearing screening, which is a sign
     * of potential hearing issues" adds enough words of its own that it covers
     * only half of itself, and the polarity check skipped it. Against the six
     * content words of the source sentence it is paraphrasing, the overlap is
     * two thirds -- which is what "this sentence is a rewrite of that one"
     * actually means.
     */
    private fun overlapOf(words: Set<String>, candidate: String): Double {
        val other = contentWords(candidate)
        if (other.isEmpty() || words.isEmpty()) return 0.0
        val shared = words.count { it in other }.toDouble()
        return maxOf(shared / words.size, shared / other.size)
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    /** Where one claim ends and the next begins inside a sentence. */
    private val CLAUSE_BREAK = Regex(
        """[,;:]\s+|\s+(?:but|while|although|whereas|however|though)\s+""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The sentences, split again at clause boundaries.
     *
     * A clause too short to carry a claim is dropped rather than matched, and
     * if nothing survives the split the sentences are used as they were.
     */
    private fun clauses(text: String): List<String> {
        val out = sentences(text)
            .flatMap { it.split(CLAUSE_BREAK) }
            .filter { contentWords(it).size >= 3 }
        return out.ifEmpty { sentences(text) }
    }

    private val negations = setOf(
        "not", "no", "never", "cannot", "cant", "dont", "doesnt", "didnt",
        "isnt", "arent", "wasnt", "wont", "shouldnt", "without", "neither",
        "nor", "none", "nothing",
        // "avoid" and "rarely" were here and came out again: they are ordinary
        // content words in this corpus ("foods to avoid before one year"), and
        // treating them as negation markers rejected faithful rewrites.
    )

    private fun isNegated(sentence: String): Boolean = sentence.lowercase()
        .replace("'", "")
        .split(Regex("[^a-z]+"))
        .any { it in negations }
    }

/**
 * Turning what a small model emits into something a card can render.
 *
 * Pure, and separate from [LlmBridge], because every rule in it came from
 * something that reached the screen and none of them can be checked on a
 * device without loading half a gigabyte of weights first.
 */
internal object ModelText {

    /** Prefixes the model repeats back before starting its answer. */
    private val LEAD_INS = listOf("ANSWER:", "Answer:", "ANSWER", "Rules:", "NOTES:")

    /** Where it stops answering and starts writing itself a fresh prompt. */
    private val RUN_ONS = listOf("\nQUESTION:", "\nNOTES:", "\nRules:", "\nQ:")

    /** Openings that mean the model declined instead of answering. */
    private val REFUSALS = listOf(
        "i'm sorry", "im sorry", "i am sorry", "i cannot assist",
        "i can't assist", "i cant assist", "i cannot help with",
        "i can't help with", "i'm unable to", "i am unable to",
        "as an ai", "as a language model", "i do not have the ability",
        "i don't have the ability",
    )

    /**
     * Did it decline rather than answer?
     *
     * Checked on the first stretch only. A refusal is how these models open;
     * the same words later in a paragraph are usually an ordinary hedge, and
     * hedging is exactly what this app wants them to do.
     */
    fun isRefusal(text: String): Boolean {
        val opening = text.take(80).lowercase()
        return REFUSALS.any { it in opening }
    }

    fun tidy(raw: String): String {
        var text = raw.trim()
        LEAD_INS.forEach { if (text.startsWith(it)) text = text.removePrefix(it).trim() }
        RUN_ONS.forEach {
            val cut = text.indexOf(it)
            if (cut > 0) text = text.take(cut)
        }

        // Real line breaks first, then the literal two-character forms. These
        // models emit backslash-n *as text* often enough that it reached the
        // screen, in answers reading "...at night.\nIf it continues...". The
        // order matters: converting the literals first would leave real CRLFs
        // behind for the paragraph splitter to trip over.
        text = text.replace("\r\n", "\n")
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", " ")

        return text
            // Emphasis before bullets, and not the other way round. Stripping
            // leading markers first ate the opening asterisk of "**Bold**",
            // which then no longer matched the bold rule and reached the card
            // as "*Bold**".
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            // Bullets are normalised, not removed. The card can draw a real
            // bulleted list, and flattening a list into a paragraph loses the
            // one piece of structure worth keeping. A marker only counts when
            // whitespace follows it, so emphasis is never mistaken for a list.
            .replace(Regex("(?m)^\\s*[*\u2022]\\s+"), "- ")
            .replace(Regex("(?m)^\\s*-\\s+"), "- ")
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines().joinToString("\n") { it.trimEnd() }
            .trim()
    }
}
