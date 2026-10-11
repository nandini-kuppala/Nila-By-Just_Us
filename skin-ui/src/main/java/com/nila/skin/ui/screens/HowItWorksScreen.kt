package com.nila.skin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.ai.VisionModel
import com.nila.skin.core.color.Lab
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.rules.Source
import com.nila.skin.core.synth.Synth
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.components.FactCard
import com.nila.skin.ui.components.Heading
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.RowDivider
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs

@Composable
fun HowItWorksScreen(back: () -> Unit, go: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val status by SkinServices.vision.status.collectAsState()
    var test by remember { mutableStateOf<SelfTest?>(null) }
    var running by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val evalRows = remember { androidx.compose.runtime.mutableStateListOf<com.nila.skin.ui.VisionEval.Row>() }
    var evalRunning by remember { mutableStateOf(false) }
    var evalMissing by remember { mutableStateOf(false) }

    SkinScaffold("How Nila decides", onBack = back) {
        ScreenHero("Four layers, one rule", "The AI describes. The maths measures. Published guidelines decide the next step. Your doctor diagnoses.")
        FactCard("1 · The AI describes", Icons.Outlined.AutoAwesome, Tone.BLUE, listOf(
            "Intern3.5-VL-2B runs on the phone's Hexagon NPU (Qualcomm AI Hub build, GenieX runtime).",
            "It answers closed questions: colour, texture, pattern, which foods are on a plate.",
            "It is never asked what condition a baby has, and parents confirm anything it says.",
            "MediaPipe face landmarks (GPU) find the pupils for the eye check, and where the baby was looking (the main cause of false white glows).",
            "For a plate, each food it lists is double-checked with a yes/no question, which small models answer more reliably.",
            "Its description is compared with the maths; when they disagree, the parent is asked to look again.",
        ))
        FactCard("2 · The maths measures", Icons.Outlined.Calculate, Tone.TEAL, listOf(
            "Colour in CIELAB: a* is redness, b* is yellowness, L* lightness.",
            "Every spot is compared with the baby's own skin in a ring around it, not a population average.",
            "Stool and eye-white colours are corrected against something white in the photo.",
            "Uneven light is modelled: the surrounding skin is fitted with a plane (least squares, outliers trimmed), so each pixel is compared with the skin right next to it.",
            "Stool and eye-white photos are corrected for the colour and the brightness of the light, using the white nappy or a tissue.",
            "Colour distance by CIEDE2000; size from a coin if one is in the photo; trends use a robust line through every photo (Theil–Sen).",
            "Food patterns: exposure windows, then Fisher's exact test, so chance alone can't make a pattern.",
        ))
        FactCard("3 · Guidelines decide", Icons.Outlined.Gavel, Tone.PEACH, listOf(
            "Only a parent's checklist answer can trigger \"get help now\"; photos can at most suggest \"show your doctor\".",
            "Every piece of advice names its source; tap it on any result for the full citation.",
        ))
        FactCard("4 · Your doctor diagnoses", Icons.Outlined.LocalHospital, Tone.SLATE, listOf(
            "Nila's output is a next step and a record: photos, changes, answers and foods, in one PDF.",
        ))
        FactCard("What Nila never does", Icons.Outlined.Block, Tone.PINK, listOf(
            "Name a disease or say \"it's probably X\".",
            "Tell you to skip the doctor when a warning sign is present.",
            "Estimate bilirubin from a photo (camera apps miss too many high levels on darker skin).",
            "Judge the glass test from a photo.",
            "Send a photo anywhere. Everything stays on this phone.",
        ))

        OutlinePillButton("Demo lab: 15 everyday moments, step by step", onClick = { go("demo") }, modifier = Modifier.fillMaxWidth())
        Heading("The guidelines")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            Source.entries.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(s.short, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MutedText(s.full)
                }
            }
        }

        Heading("On-device AI")
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(Icons.Outlined.Memory, null)
                Text(statusText(status), style = MaterialTheme.typography.bodyMedium)
            }
            if (status !is VisionModel.Status.Ready && status != VisionModel.Status.Absent) OutlinePillButton(if (loading) "Loading..." else "Load the model now", enabled = !loading, onClick = {
                loading = true; scope.launch { SkinServices.vision.ensureLoaded(); loading = false }
            }, modifier = Modifier.fillMaxWidth())
            MutedText("Face landmarks: MediaPipe Face Landmarker" + if (SkinServices.eyes.delegate == "none") ", loads on the first eye check." else " on the ${SkinServices.eyes.delegate}.")
        }

        Heading("Self-test on this phone")
        MutedText("Runs the measurement code on pictures whose true answer is known, including the same patch on six skin tones. Judges can run it themselves.")
        PillButton(if (running) "Running..." else "Run the self-test", enabled = !running, icon = Icons.Outlined.PlayArrow, modifier = Modifier.fillMaxWidth(), onClick = {
            running = true
            scope.launch { test = withContext(Dispatchers.Default) { SelfTest.run() }; running = false }
        })
        test?.let { t ->
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Icon(Icons.Outlined.Balance, null)
                    Text("Same patch, six skin tones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                TableRow(listOf("Tone", "L*", "True size", "Found", "Spots", "Redness true → found", ""), header = true)
                t.tones.forEach { r ->
                    TableRow(listOf(r.name, "%.0f".format(r.l), "%.1f%%".format(r.trueCov * 100), "%.1f%%".format(r.cov * 100),
                        "${r.spots}", "%.1f → %.1f".format(r.trueDa, r.da), if (r.pass) "✓" else "✗"))
                }
                MutedText("Size = share of the circle covered by the patch. Redness = Δa* against the same baby's skin; darker skin shows a smaller shift, as real redness does.")
                RowDivider()
                Text("Stool card under warm, daylight, cool and dim-warm light: ${t.stoolCorrect} of ${t.stoolTotal} in the right group", style = MaterialTheme.typography.bodyMedium)
                Text("Brown mark with light from one side, six tones: found ${t.sideLightFound} of 6 (one flat skin colour: ${t.sideLightFlat} of 6)", style = MaterialTheme.typography.bodyMedium)
                Text("Eye reflex (red / white / dark): ${t.eyeCorrect} of 3", style = MaterialTheme.typography.bodyMedium)
                MutedText("Ran in ${t.ms} ms on this phone's CPU.")
            }
        }
        Heading("Test the on-device AI")
        MutedText("Ten real baby-food photos with hand-written answers. The AI's list alone, against the list after the three cross-checks. Precision: of the foods shown, how many are really there. Recall: of the main foods, how many were found. Takes a minute or two on the NPU.")
        PillButton(if (evalRunning) "Testing... ${evalRows.size} of ${com.nila.skin.ui.VisionEval.truths.size}" else "Test the AI on 10 baby-food photos", enabled = !evalRunning,
            icon = Icons.Outlined.AutoAwesome, modifier = Modifier.fillMaxWidth(), onClick = {
                evalRunning = true; evalRows.clear(); evalMissing = false
                scope.launch {
                    val r = withContext(Dispatchers.Default) { com.nila.skin.ui.VisionEval.run { row -> scope.launch { evalRows.add(row) } } }
                    if (r == null) evalMissing = true
                    evalRunning = false
                }
            })
        if (evalMissing) MutedText("The model isn't available on this phone, so there is nothing to test.")
        if (evalRows.isNotEmpty()) NilaCard(modifier = Modifier.fillMaxWidth()) {
            val t = com.nila.skin.ui.VisionEval.totals(evalRows)
            Text("AI list alone: precision ${t.pct(t.listedRight, t.listed)}% (${t.listedRight}/${t.listed}), recall ${t.pct(t.coreListed, t.coreTotal)}% (${t.coreListed}/${t.coreTotal})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("After cross-checks (pre-ticked): precision ${t.pct(t.keptRight, t.kept)}% (${t.keptRight}/${t.kept}), recall ${t.pct(t.coreKept, t.coreTotal)}% (${t.coreKept}/${t.coreTotal})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Main foods on screen for the parent, including colour suggestions: ${t.pct(t.coreShown, t.coreTotal)}% (${t.coreShown}/${t.coreTotal})", style = MaterialTheme.typography.bodyMedium)
            RowDivider()
            evalRows.forEach { r ->
                Text(r.label, style = MaterialTheme.typography.labelLarge)
                MutedText("AI: ${r.listed.joinToString().ifEmpty { "nothing" }} → kept: ${r.kept.joinToString().ifEmpty { "nothing" }}" +
                    if (r.suggested.isNotEmpty()) " · colour suggests: ${r.suggested.joinToString()}" else "")
            }
            MutedText("Synthetic tests check the maths; this checks the AI on real photos. Ten photos is a small test, shown as it is.")
        }
        FactCard("Privacy", Icons.Outlined.Lock, Tone.SLATE, listOf(
            "Photos live in the app's private storage, never in the gallery or a cloud.",
            "The AI sees one photo for one answer; the temporary copy is deleted straight after.",
            "Reports leave the phone only when you share them.",
        ))
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    val weights = listOf(0.8f, 0.6f, 1.1f, 1f, 0.7f, 1.8f, 0.4f)
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { i, c ->
            Text(c, modifier = Modifier.weight(weights[i]), style = MaterialTheme.typography.labelMedium,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun statusText(s: VisionModel.Status) = when (s) {
    is VisionModel.Status.Ready -> "${s.name}, loaded in ${s.loadMs / 1000.0} s"
    is VisionModel.Status.Loading -> "Loading onto the NPU, attempt ${s.attempt} of ${s.of}"
    VisionModel.Status.Installed -> "Intern3.5-VL-2B installed; loads on first use (about 10-20 s)"
    VisionModel.Status.Absent -> "Model not installed on this phone. Every check still works with maths and checklists."
    is VisionModel.Status.Failed -> "Unavailable: ${s.reason}"
}

data class SelfTest(val tones: List<ToneRow>, val stoolCorrect: Int, val stoolTotal: Int, val eyeCorrect: Int, val ms: Long,
                    val sideLightFound: Int = 0, val sideLightFlat: Int = 0) {
    data class ToneRow(val name: String, val l: Double, val trueCov: Double, val cov: Double, val spots: Int, val trueDa: Double, val da: Double, val pass: Boolean)

    companion object {
        fun run(): SelfTest {
            val t0 = System.currentTimeMillis()
            val spot = Synth.Spot(0.5, 0.5, 0.12)
            val circle = NCircle(0.5, 0.5, 0.22)
            val trueCov = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22)
            val tones = Synth.TONES.mapIndexed { i, (name, tone) ->
                val dA = Synth.rednessFor(tone, 14.0)
                val r = SkinMeasure.measure(Synth.skin(400, tone, listOf(spot), deltaA = dA, seed = 100 + i), circle)
                ToneRow(name, tone.l, trueCov, r.coverage, r.spots, dA, r.deltaA, abs(r.coverage - trueCov) < 0.06 && r.spots == 1 && abs(r.deltaA - dA) < 3)
            }
            var ok = 0; var total = 0
            for (s in StoolCard.swatches) for (light in listOf(Synth.WARM, Synth.NEUTRAL, Synth.COOL, doubleArrayOf(0.4, 0.32, 0.22))) {
                total++
                if (StoolCard.read(Synth.onNappy(240, s.lab, light), NCircle(0.5, 0.5, 0.15)).group == s.group) ok++
            }
            val pupil = NCircle(0.5, 0.5, 0.18)
            val eyes = listOf(Lab(45.0, 60.0, 45.0) to Reflex.RED, Lab(88.0, 2.0, 18.0) to Reflex.WHITE, Lab(10.0, 1.0, 1.0) to Reflex.DARK)
                .count { (lab, want) -> EyeReflex.read(Synth.eye(160, lab), pupil).reflex == want }
            var side = 0; var sideFlat = 0
            Synth.TONES.forEachIndexed { i, (_, tone) ->
                val img = Synth.skin(320, tone, listOf(spot), deltaA = 1.0, deltaL = -12.0, seed = 500 + i, gradient = 0.4)
                if (abs(SkinMeasure.measure(img, circle).coverage - trueCov) < 0.06) side++
                if (abs(SkinMeasure.measure(img, circle, lightingModel = false).coverage - trueCov) < 0.06) sideFlat++
            }
            return SelfTest(tones, ok, total, eyes, System.currentTimeMillis() - t0, side, sideFlat)
        }
    }
}
