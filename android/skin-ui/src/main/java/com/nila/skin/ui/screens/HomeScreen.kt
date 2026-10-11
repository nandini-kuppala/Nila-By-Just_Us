package com.nila.skin.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.IconBadge
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
        if (baby == null) {
            ScreenHero(
                title = "Notice early. Stay calm. Show your doctor.",
                body = "For the little things between check-ups. Nila describes and measures; your doctor diagnoses.",
            )
            ModelStatusPill(status)
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Text("Let's start with your baby", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Their age decides which checks apply: a fever means something different at 2 months than at 8.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PillButton("Add your baby", onClick = { go("profile") }, icon = Icons.Outlined.ChildCare, modifier = Modifier.fillMaxWidth())
                OutlinePillButton("Quick demo", onClick = { go("quick") }, icon = Icons.Outlined.Science, modifier = Modifier.fillMaxWidth())
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

        // One line, then the checks: the details live on each check's own page.
        MutedText("Notice early. Stay calm. Show your doctor.")
        BabyCard(baby, onEdit = { go("profile") })
        TodayCard(baby, go)

        SectionTitle("Checks")
        val checks = listOf(
            Check("Skin diary", "Track a mark", Icons.Outlined.Spa, Tone.TEAL, "skin"),
            Check("Nappy colour", "Stool card", Icons.Outlined.Science, Tone.BLUE, "stool"),
            Check("Eye flash", "Red or white glow", Icons.Outlined.RemoveRedEye, Tone.PLUM, "eyes"),
            Check("Yellow check", "Newborn jaundice", Icons.Outlined.WbSunny, Tone.PEACH, "yellow"),
            Check("Food & reactions", "Spot a pattern", Icons.Outlined.Restaurant, Tone.PINK, "food"),
            Check("Meal plate", "What's in the bowl", Icons.Outlined.LocalDrink, Tone.PEACH, "meal"),
        ).let { list ->
            // In the first month the yellow check matters most, so it leads.
            if (baby.ageDays() <= 28) list.sortedByDescending { it.route == "yellow" } else list
        }
        checks.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                pair.forEach { c -> CheckTile(c, Modifier.weight(1f).fillMaxHeight()) { go(c.route) } }
            }
        }

        SectionTitle("For the doctor")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            SmallTile("Report", Icons.Outlined.Description, Tone.TEAL, Modifier.weight(1f).fillMaxHeight()) { go("report") }
            SmallTile("How it decides", Icons.Outlined.Lightbulb, Tone.BLUE, Modifier.weight(1f).fillMaxHeight()) { go("how") }
            SmallTile("Usually harmless", Icons.Outlined.Spa, Tone.PINK, Modifier.weight(1f).fillMaxHeight()) { go("learn") }
        }
        NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.PLUM, verticalSpacing = 0.dp) {
            ListRow("Quick demo", subtitle = "Every check: photo in, result out", icon = Icons.Outlined.AutoAwesome, tone = Tone.PLUM, onClick = { go("quick") })
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
        VisionModel.Status.Absent -> "No AI model · every check still works" to Tone.SLATE
        is VisionModel.Status.Failed -> "AI unavailable · every check still works" to Tone.SLATE
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
    if (list.isEmpty()) return
    SectionTitle("Today")
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
        list.forEachIndexed { i, t ->
            if (i > 0) RowDivider()
            ListRow(t.title, subtitle = t.subtitle, onClick = { go(t.route) })
        }
    }
}

private data class Check(val title: String, val hint: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tone: Tone, val route: String)

/** One check as a tile: what it is, in two or three words. */
@Composable
private fun CheckTile(c: Check, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NilaCard(modifier = modifier, onClick = onClick, verticalSpacing = 10.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        IconBadge(c.icon, c.tone, size = 44.dp, iconSize = 22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(c.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    NilaCard(modifier = modifier, onClick = onClick, verticalSpacing = 8.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        IconBadge(icon, tone, size = 36.dp, iconSize = 18.dp)
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
