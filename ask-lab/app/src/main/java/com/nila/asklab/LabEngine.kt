package com.nila.asklab

import android.content.Context
import com.nila.assistant.AskPipeline
import com.nila.assistant.DemoSeedMemory
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.NpuLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine, the pipeline and the run state, held for the life of the process.
 *
 * It started inside the view model, which is where it belongs in an app. It is
 * not where it belongs here: the phone this is measured on keeps another app in
 * the foreground, the activity behind it gets destroyed, and an eval that lives
 * in a view model dies with it -- twenty minutes into a run, with no report and
 * no error. A measurement that cannot survive losing the screen is not a
 * measurement.
 */
object LabEngine {

    data class Message(
        val fromUser: Boolean,
        val text: String,
        val reply: AskPipeline.Reply? = null,
        val streaming: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob())

    private lateinit var app: Context
    private lateinit var memory: DemoSeedMemory
    private lateinit var llm: NpuLlm
    private lateinit var pipeline: AskPipeline

    private val _engine = MutableStateFlow<NpuLlm.State>(NpuLlm.State.Absent)
    val engine: StateFlow<NpuLlm.State> = _engine.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _lastReport = MutableStateFlow<String?>(null)
    val lastReport: StateFlow<String?> = _lastReport.asStateFlow()

    private var started = false

    val modelPath: String get() = llm.modelFile().absolutePath

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        memory = DemoSeedMemory(app)
        llm = NpuLlm(app)
        pipeline = AskPipeline(KnowledgeIndex.load(app), memory, llm)
        scope.launch { llm.start { _engine.value = it } }
    }

    fun ask(question: String) {
        if (question.isBlank() || _busy.value) return
        scope.launch {
            _busy.value = true
            awaitEngine()
            _messages.value = _messages.value + Message(true, question)
            val at = _messages.value.size
            _messages.value = _messages.value + Message(false, "", streaming = true)

            val partial = StringBuilder()
            val reply = pipeline.ask(question) { token ->
                partial.append(token)
                val snapshot = partial.toString()
                _messages.value = _messages.value.toMutableList()
                    .also { it[at] = Message(false, snapshot, streaming = true) }
            }
            _messages.value = _messages.value.toMutableList()
                .also { it[at] = Message(false, reply.text, reply) }
            File(app.getExternalFilesDir(null), "answer.json")
                .writeText(describe(question, reply).toString(2))
            _busy.value = false
        }
    }

    fun clearChat() {
        scope.launch {
            _messages.value = emptyList()
            memory.forgetConversation()
            llm.reset()
        }
    }

    /**
     * Run every probe and write the report.
     *
     * Conversation memory is cleared before each probe except the follow-ups,
     * so the run measures the pipeline rather than the order the questions
     * happened to be asked in.
     */
    fun runEval(onDone: () -> Unit = {}) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            awaitEngine()
            val results = JSONArray()
            var agreed = 0
            var checked = 0
            val dir = app.getExternalFilesDir(null)!!
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

            // Written after every probe, not at the end. The phone this runs on
            // kills a backgrounded app holding 1.2 GB of weights, foreground
            // service or not, and it did so twice with forty minutes of answers
            // in a StringBuilder. A partial report is a result; a lost one is
            // an afternoon.
            fun snapshot(done: Int) {
                val document = JSONObject().apply {
                    put("device", android.os.Build.MODEL)
                    put("engine", _engine.value.toString())
                    put("completed", "$done / ${EvalSet.probes.size}")
                    put("routingAgreement", "$agreed / $checked")
                    put("results", results)
                }
                File(dir, "eval-$stamp.json").writeText(document.toString(2))
                if (done == EvalSet.probes.size) {
                    File(dir, "eval-latest.json").writeText(document.toString(2))
                }
            }

            EvalSet.probes.forEachIndexed { i, probe ->
                _progress.value = "${i + 1} / ${EvalSet.probes.size}  ${probe.question.take(40)}"
                if (!probe.follows) {
                    memory.forgetConversation()
                    llm.reset()
                }
                val reply = pipeline.ask(probe.question)
                if (probe.expect != null) {
                    checked++
                    if (reply.tier == probe.expect) agreed++
                }
                results.put(describe(probe.question, reply).apply {
                    put("note", probe.note)
                    put("follows", probe.follows)
                    put("expected", probe.expect?.name ?: "-")
                    put("agrees", probe.expect == null || probe.expect == reply.tier)
                })
                snapshot(i + 1)
            }

            _lastReport.value = "eval-$stamp.json"
            _progress.value = "done: $agreed / $checked routed as expected"
            _busy.value = false
            onDone()
        }
    }

    fun retry() {
        scope.launch { llm.start { _engine.value = it } }
    }

    private suspend fun awaitEngine(timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (_engine.value) {
                is NpuLlm.State.Ready, is NpuLlm.State.Failed -> return
                else -> delay(250)
            }
        }
    }

    private fun describe(question: String, reply: AskPipeline.Reply): JSONObject =
        JSONObject().apply {
            put("question", question)
            put("tier", reply.tier.name)
            put("answer", reply.text)
            put("sources", JSONArray(reply.sources))
            put("cautions", JSONArray(reply.cautions))
            put("guard", reply.trace.guard)
            put("overlap", reply.trace.groundingOverlap)
            put("route", reply.trace.route)
            put("hits", reply.trace.hits)
            put("topScore", reply.trace.topScore)
            put("personal", reply.trace.personal)
            put("recordHits", reply.trace.recordHits)
            put("factsUsed", reply.trace.factsUsed)
            put("turnsUsed", reply.trace.turnsUsed)
            reply.trace.specificity?.let {
                put("matchedTerms", JSONArray(it.matchedTerms))
                put("topicalTerms", JSONArray(it.topicalTerms))
                put("rarestTopicalDf", it.rarestTopicalDf)
            }
            put("retrievalMs", reply.trace.retrievalMs)
            put("totalMs", reply.trace.totalMs)
            reply.trace.generation?.let { g ->
                put("promptTokens", g.promptTokens)
                put("generatedTokens", g.generatedTokens)
                put("prefillTps", g.prefillTokensPerSecond)
                put("decodeTps", g.decodeTokensPerSecond)
                put("ttftMs", g.ttftMs)
                put("stopReason", g.stopReason)
                put("raw", g.text)
            }
        }
}
