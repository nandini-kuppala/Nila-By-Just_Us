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
import androidx.compose.ui.text.font.FontFamily
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
fun HowItWorksScreen(back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by SkinServices.vision.status.collectAsState()
    var test by remember { mutableStateOf<SelfTest?>(null) }
    var running by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    SkinScaffold("How Nila decides", onBack = back) {
        ScreenHero("Four layers, one rule", "The AI describes. The maths measures. Published guidelines decide the next step. Your doctor diagnoses.")
        FactCard("1 · The AI describes", Icons.Outlined.AutoAwesome, Tone.BLUE, listOf(
            "Intern3.5-VL-2B runs on the phone's Hexagon NPU (Qualcomm AI Hub build, GenieX runtime).",
            "It answers closed questions: colour, texture, pattern, which foods are on a plate.",
            "It is never asked what condition a baby has, and parents confirm anything it says.",
            "MediaPipe face landmarks (GPU) find the pupils for the eye check.",
        ))
        FactCard("2 · The maths measures", Icons.Outlined.Calculate, Tone.TEAL, listOf(
            "Colour in CIELAB: a* is redness, b* is yellowness, L* lightness.",
            "Every spot is compared with the baby's own skin in a ring around it, not a population average.",
            "Stool and eye-white colours are corrected against something white in the photo.",
            "Colour distance by CIEDE2000; size from a coin if one is in the photo; trends need a change bigger than re-photo noise.",
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
            MutedText("Face landmarks: MediaPipe Face Landmarker. Delegate in use: ${SkinServices.eyes.delegate}.")
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
                Text("Tone   L*   true%  found%  spots  Δa* true/found", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                t.tones.forEach { r ->
                    Text("%-5s %4.0f  %5.1f  %5.1f   %d     %4.1f/%4.1f  %s".format(r.name, r.l, r.trueCov * 100, r.cov * 100, r.spots, r.trueDa, r.da, if (r.pass) "✓" else "✗"),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                }
                RowDivider()
                Text("Stool card under warm, daylight and cool light: ${t.stoolCorrect} of ${t.stoolTotal} in the right group", style = MaterialTheme.typography.bodyMedium)
                Text("Eye reflex (red / white / dark): ${t.eyeCorrect} of 3", style = MaterialTheme.typography.bodyMedium)
                MutedText("Ran in ${t.ms} ms on this phone's CPU.")
            }
        }
        FactCard("Privacy", Icons.Outlined.Lock, Tone.SLATE, listOf(
            "Photos live in the app's private storage, never in the gallery or a cloud.",
            "The AI sees one photo for one answer; the temporary copy is deleted straight after.",
            "Reports leave the phone only when you share them.",
        ))
    }
}

private fun statusText(s: VisionModel.Status) = when (s) {
    is VisionModel.Status.Ready -> "${s.name}, loaded in ${s.loadMs / 1000.0} s"
    is VisionModel.Status.Loading -> "Loading onto the NPU, attempt ${s.attempt} of ${s.of}"
    VisionModel.Status.Installed -> "Intern3.5-VL-2B installed; loads on first use (about 10-20 s)"
    VisionModel.Status.Absent -> "Model not installed on this phone. Every check still works with maths and checklists."
    is VisionModel.Status.Failed -> "Unavailable: ${s.reason}"
}

data class SelfTest(val tones: List<ToneRow>, val stoolCorrect: Int, val stoolTotal: Int, val eyeCorrect: Int, val ms: Long) {
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
            for (s in StoolCard.swatches) for (light in listOf(Synth.WARM, Synth.NEUTRAL, Synth.COOL)) {
                total++
                if (StoolCard.read(Synth.onNappy(240, s.lab, light), NCircle(0.5, 0.5, 0.15)).group == s.group) ok++
            }
            val pupil = NCircle(0.5, 0.5, 0.18)
            val eyes = listOf(Lab(45.0, 60.0, 45.0) to Reflex.RED, Lab(88.0, 2.0, 18.0) to Reflex.WHITE, Lab(10.0, 1.0, 1.0) to Reflex.DARK)
                .count { (lab, want) -> EyeReflex.read(Synth.eye(160, lab), pupil).reflex == want }
            return SelfTest(tones, ok, total, eyes, System.currentTimeMillis() - t0)
        }
    }
}
