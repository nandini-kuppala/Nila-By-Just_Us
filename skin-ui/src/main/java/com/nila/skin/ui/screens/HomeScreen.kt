package com.nila.skin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.ai.VisionModel
import com.nila.skin.core.rules.Step
import com.nila.skin.data.BabyEntity
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.ageText
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.ListRow
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.NotADiagnosis
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.RowDivider
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SectionTitle
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.components.StepPill
import com.nila.skin.ui.components.TonePill
import com.nila.skin.ui.demo.DemoData
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.launch

/** A thing that needs the parent today, worked out from what is logged. */
data class TodayItem(val title: String, val subtitle: String, val route: String, val step: Step?)

@Composable
fun HomeScreen(baby: BabyEntity?, go: (String) -> Unit, onExit: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var seeding by remember { mutableStateOf(false) }
    val status by SkinServices.vision.status.collectAsState()

    SkinScaffold(title = "Little Signs", onBack = onExit) {
        ScreenHero(
            title = "Notice early. Stay calm. Show your doctor.",
            body = "For the little things between check-ups: is this normal, is it changing, was it the food? Photos, simple checks and published guidelines, all on this phone. Nila describes and measures; your doctor diagnoses.",
        )
        ModelStatusPill(status)

        if (baby == null) {
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Text("Let's start with your baby", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Their age decides which checks apply: a fever means something different at 2 months than at 8.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PillButton("Add your baby", onClick = { go("profile") }, icon = Icons.Outlined.ChildCare, modifier = Modifier.fillMaxWidth())
                OutlinePillButton("Demo lab: everyday moments, one tap each", onClick = { go("demo") }, icon = Icons.Outlined.Science, modifier = Modifier.fillMaxWidth())
                OutlinePillButton(if (seeding) "Loading demo..." else "Demo mode for judges", enabled = !seeding, onClick = {
                    seeding = true
                    scope.launch { DemoData.load(); seeding = false }
                }, icon = Icons.Outlined.AutoAwesome, modifier = Modifier.fillMaxWidth())
                if (seeding) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp)); Text("Creating a week of demo history on the phone...")
                }
            }
            NotADiagnosis()
            return@SkinScaffold
        }

        BabyCard(baby, onEdit = { go("profile") })
        TodayCard(baby, go)

        SectionTitle("Checks")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            val newborn = baby.ageDays() <= 28
            if (newborn) {
                ListRow("Yellow check", subtitle = "Newborn jaundice: eyes, palms and soles in daylight (WHO IMNCI)", icon = Icons.Outlined.WbSunny, tone = Tone.PEACH, onClick = { go("yellow") })
                RowDivider()
            }
            ListRow("Skin diary", subtitle = "Photo a rash, patch or birthmark; track if it's spreading", icon = Icons.Outlined.Spa, tone = Tone.TEAL, onClick = { go("skin") })
            RowDivider()
            ListRow("Food & reactions", subtitle = "New foods, check-ins after them, patterns and allergens", icon = Icons.Outlined.Restaurant, tone = Tone.PINK, onClick = { go("food") })
            RowDivider()
            ListRow("Meal plate", subtitle = "Photo the plate: nutrients and food groups (ICMR, WHO)", icon = Icons.Outlined.LocalDrink, tone = Tone.PEACH, onClick = { go("meal") })
            RowDivider()
            ListRow("Nappy colour check", subtitle = "The stool colour card that catches liver problems early", icon = Icons.Outlined.Science, tone = Tone.BLUE, onClick = { go("stool") })
            RowDivider()
            ListRow("Eye flash check", subtitle = "A white glow in flash photos is worth a red reflex test", icon = Icons.Outlined.RemoveRedEye, tone = Tone.PLUM, onClick = { go("eyes") })
            if (!newborn) {
                RowDivider()
                ListRow("Yellow check", subtitle = "Jaundice signs in newborns (WHO IMNCI)", icon = Icons.Outlined.WbSunny, tone = Tone.PEACH, onClick = { go("yellow") })
            }
            RowDivider()
            ListRow("Glass test", subtitle = "For a rash with fever: does it fade under a glass?", icon = Icons.Outlined.WineBar, tone = Tone.SLATE, onClick = { go("glass") })
        }

        NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.PLUM, verticalSpacing = 0.dp) {
            ListRow("Demo lab", subtitle = "15 everyday moments, each run live: is this poo normal, tiny spots, a mark to watch, red cheeks after a new food, enough iron...", icon = Icons.Outlined.Science, tone = Tone.PLUM, onClick = { go("demo") })
        }

        SectionTitle("For the doctor")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            ListRow("Doctor report", subtitle = "One page: photos, timeline, answers and foods. Shared only by you.", icon = Icons.Outlined.Description, tone = Tone.TEAL, onClick = { go("report") })
            RowDivider()
            ListRow("How Nila decides", subtitle = "What the AI does, what the maths does, and which guideline says what", icon = Icons.Outlined.Lightbulb, tone = Tone.BLUE, onClick = { go("how") })
            RowDivider()
            ListRow("Common and harmless", subtitle = "The everyday skin changes of the first year, and when they're not", icon = Icons.Outlined.Spa, tone = Tone.PINK, onClick = { go("learn") })
        }

        if (baby.demo) {
            InfoNote("Demo mode: a made-up baby with a week of synthetic photos and logs. Every number on screen was measured on this phone from those photos.",
                icon = Icons.Outlined.AutoAwesome, tone = Tone.PLUM, title = "Demo data")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinePillButton(if (seeding) "Resetting..." else "Reset demo", enabled = !seeding, onClick = {
                    seeding = true; scope.launch { DemoData.load(); seeding = false }
                }, modifier = Modifier.weight(1f))
                OutlinePillButton("Clear all", onClick = { scope.launch { DemoData.clear() } }, modifier = Modifier.weight(1f))
            }
        }
        NotADiagnosis()
    }
}

@Composable
fun ModelStatusPill(status: VisionModel.Status) {
    val (text, tone) = when (status) {
        is VisionModel.Status.Ready -> "On-device AI ready · NPU" to Tone.TEAL
        is VisionModel.Status.Loading -> "Loading AI onto the NPU (${status.attempt}/${status.of})" to Tone.BLUE
        VisionModel.Status.Installed -> "On-device AI installed · loads on first use" to Tone.BLUE
        VisionModel.Status.Absent -> "AI model not installed · maths and checklists still work" to Tone.SLATE
        is VisionModel.Status.Failed -> "AI unavailable · maths and checklists still work" to Tone.SLATE
    }
    TonePill(text, tone, dot = status is VisionModel.Status.Ready)
}

@Composable
private fun BabyCard(baby: BabyEntity, onEdit: () -> Unit) {
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
        ListRow(baby.name, subtitle = ageText(baby.ageDays()) + if (baby.preterm) " · born early" else "",
            icon = Icons.Outlined.Face, tone = Tone.TEAL, onClick = onEdit, chevron = false,
            trailing = { androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant) })
    }
}

@Composable
private fun TodayCard(baby: BabyEntity, go: (String) -> Unit) {
    var items by remember { mutableStateOf<List<TodayItem>?>(null) }
    val foods by SkinServices.dao.foods().collectAsState(initial = emptyList())
    val entries by SkinServices.dao.allEntries().collectAsState(initial = emptyList())
    val stools by SkinServices.dao.stools().collectAsState(initial = emptyList())
    LaunchedEffect(foods, entries, stools, baby) {
        // Debounced: demo loading inserts hundreds of rows in a burst.
        kotlinx.coroutines.delay(250)
        items = Today.compute(baby)
    }
    val list = items ?: return
    SectionTitle("Today")
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
        if (list.isEmpty()) {
            Text("Nothing due. Everything logged looks settled.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
        list.forEachIndexed { i, t ->
            if (i > 0) RowDivider()
            ListRow(t.title, subtitle = t.subtitle, onClick = { go(t.route) })
        }
    }
}

