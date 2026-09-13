package com.nila.asklab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nila.assistant.AskPipeline
import com.nila.assistant.NpuLlm

private val Ink = Color(0xFF101D1A)
private val Paper = Color(0xFFF7FAF8)
private val Teal = Color(0xFF0F6B60)
private val Mist = Color(0xFFE2F1ED)
private val Amber = Color(0xFF8F5C0E)
private val Rust = Color(0xFFA6231C)

/**
 * The lab, which can be driven from the screen or from the command line.
 *
 *     adb shell am start -n com.nila.asklab/.MainActivity --es ask "question"
 *     adb shell am start -n com.nila.asklab/.MainActivity --es run eval
 *
 * The scripted route exists because the phone this runs on keeps another app
 * in the foreground; a run that depends on taps landing where the screenshot
 * said they would is not a measurement.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Paper)) {
                val vm: LabViewModel = viewModel()
                LaunchedEffect(Unit) { vm.obey(intent) }
                Lab(vm)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lab(vm: LabViewModel = viewModel()) {
    val engine by vm.engine.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.evalProgress.collectAsStateWithLifecycle()
    val report by vm.lastReport.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = Paper,
        topBar = { EngineBar(engine, vm.modelPath, onRetry = vm::retryEngine) },
        bottomBar = {
            Column {
                val progressLine = progress
                if (progressLine != null) {
                    Text(
                        progressLine,
                        Modifier.fillMaxWidth().background(Mist).padding(horizontal = 12.dp, vertical = 6.dp),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Teal,
                    )
                }
                val reportLine = report
                if (reportLine != null) {
                    Text(
                        "report: $reportLine",
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Ink.copy(alpha = 0.6f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask about the baby or the mother") },
                        maxLines = 3,
                        enabled = !busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { vm.ask(draft); draft = "" },
                        enabled = !busy && draft.isNotBlank(),
                    ) { Text("Ask") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
                    TextButton(onClick = vm::runEval, enabled = !busy) { Text("Run eval set") }
                    TextButton(onClick = vm::clearChat, enabled = !busy) { Text("Clear chat") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(messages) { _, message -> Bubble(message) }
        }
    }
}

@Composable
private fun EngineBar(state: NpuLlm.State, modelPath: String, onRetry: () -> Unit) {
    val (label, colour) = when (state) {
        is NpuLlm.State.Ready ->
            "Qwen3.5-2B Q4_0 on ${state.computeUnit.uppercase()} - loaded in ${state.loadMs} ms" to Teal
        is NpuLlm.State.Failed -> "engine failed: ${state.reason}" to Rust
        NpuLlm.State.Absent -> "engine not started" to Amber
        NpuLlm.State.Initialising -> "initialising GenieX..." to Amber
        NpuLlm.State.Registering -> "registering the model..." to Amber
        NpuLlm.State.Loading -> "loading onto the NPU..." to Amber
    }
    Column(Modifier.fillMaxWidth().background(Mist).padding(12.dp)) {
        Text("Nila Ask lab", fontWeight = FontWeight.SemiBold, color = Ink)
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colour)
        if (state is NpuLlm.State.Failed) {
            Text(
                "expected at $modelPath",
                fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Ink.copy(alpha = 0.6f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun Bubble(message: LabEngine.Message) {
    if (message.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = Teal, shape = RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp),
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(message.text, Modifier.padding(12.dp), color = Color.White, fontSize = 14.sp)
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(0.95f)) {
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    message.text.ifBlank { if (message.streaming) "..." else "(nothing)" },
                    color = Ink, fontSize = 14.sp,
                )
                val sources = message.reply?.sources.orEmpty()
                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    sources.forEach { source ->
                        Text(source, fontSize = 10.sp, color = Teal)
                    }
                }
                val cautions = message.reply?.cautions.orEmpty()
                if (cautions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    cautions.forEach { caution ->
                        Text(caution, fontSize = 11.sp, color = Amber)
                    }
                }
            }
        }
        val reply = message.reply
        if (reply != null) TraceLine(reply)
    }
}

/** The reason this lab exists: every answer says how it was produced. */
@Composable
private fun TraceLine(reply: AskPipeline.Reply) {
    val t = reply.trace
    val g = t.generation
    val text = buildString {
        append(reply.tier.name)
        append("  route=").append(t.route)
        if (t.hits > 0) append("  hits=").append(t.hits)
            .append(" top=").append(String.format("%.1f", t.topScore))
        if (t.personal) append("  personal")
        if (t.recordHits > 0) append("  records=").append(t.recordHits)
        if (t.factsUsed > 0) append("  facts=").append(t.factsUsed)
        if (t.turnsUsed > 0) append("  turns=").append(t.turnsUsed)
        if (t.guard != "-") append("  guard=").append(t.guard)
        if (g != null) {
            append("\n  ").append(g.promptTokens).append(" in / ").append(g.generatedTokens)
                .append(" out  prefill ").append(String.format("%.0f", g.prefillTokensPerSecond))
                .append(" t/s  decode ").append(String.format("%.1f", g.decodeTokensPerSecond))
                .append(" t/s  ttft ").append(String.format("%.0f", g.ttftMs)).append(" ms")
                .append("  stop=").append(g.stopReason)
        }
        append("\n  total ").append(t.totalMs).append(" ms (retrieval ").append(t.retrievalMs).append(" ms)")
    }
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 4.dp),
        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        color = if (t.guard != "-" && t.guard != "ok") Rust else Ink.copy(alpha = 0.55f),
    )
}
