package com.nila.skin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.Question
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.ui.LocalSkinHost
import com.nila.skin.ui.theme.Tone
import com.nila.skin.ui.theme.stepColors
import com.nila.skin.ui.theme.toneColors

/** A screen with Nila's top bar and a scrolling column of cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    scrollKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // Each stage of a check starts at the top, not where the last one ended.
    androidx.compose.runtime.LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
    // Inside Nila the screen already sits between Nila's status-bar padding and
    // its bottom navigation, so it adds no insets of its own, and its home is
    // titled the way Nila titles a tab.
    val host = LocalSkinHost.current
    val hosted = host != null
    val noInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = if (hosted) noInsets else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = if (hosted && onBack == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    actions()
                    if (host != null && onBack == null) host.actions(this)
                },
                windowInsets = if (hosted) noInsets else TopAppBarDefaults.windowInsets,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (hosted) scheme.surface else scheme.background,
                    actionIconContentColor = scheme.onSurface,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .then(if (hosted) Modifier else Modifier.navigationBarsPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

fun stepIcon(step: Step): ImageVector = when (step) {
    Step.WATCH -> Icons.Outlined.CheckCircle
    Step.RECHECK -> Icons.Outlined.Schedule
    Step.DOCTOR_SOON, Step.DOCTOR_TODAY -> Icons.Outlined.LocalHospital
    Step.URGENT -> Icons.Outlined.LocalHospital
}

/** A small pill naming a step, for lists. */
@Composable
fun StepPill(step: Step, modifier: Modifier = Modifier) {
    val c = stepColors(step)
    Row(
        modifier = modifier.clip(CircleShape).background(c.container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(stepIcon(step), contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
        Text(step.short, style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The result card: one next step, why, what to do. Reasons each carry the
 * guideline they came from, tappable for the full citation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdviceCard(advice: Advice, modifier: Modifier = Modifier) {
    val c = stepColors(advice.step)
    var cite by remember { mutableStateOf<Source?>(null) }
    Column(
        modifier = modifier.fillMaxWidth().clip(CardShape).background(c.container).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(stepIcon(advice.step), contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
            Text(advice.heading, style = MaterialTheme.typography.headlineSmall, color = c.onContainer)
        }
        if (advice.reasons.isEmpty()) {
            Text("Nothing you told us, and nothing in the photo, needs a doctor right now.",
                style = MaterialTheme.typography.bodyMedium, color = c.onContainer)
        }
        advice.reasons.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("• " + r.text, style = MaterialTheme.typography.bodyMedium, color = c.onContainer)
                val src = r.source
                Text(
                    if (src != null) "Rule: ${src.short}" else "Worked out by Nila from your own photos and logs",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accent,
                    modifier = Modifier.padding(start = 12.dp).then(if (src != null) Modifier.clickable { cite = src } else Modifier),
                )
            }
        }
        RowDividerOn(c.onContainer.copy(alpha = 0.15f))
        Text("What to do", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.onContainer)
        advice.doNow.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = c.onContainer) }
    }
    cite?.let { s ->
        AlertDialog(
            onDismissRequest = { cite = null },
            confirmButton = { TextButton(onClick = { cite = null }) { Text("OK") } },
            title = { Text(s.short) },
            text = { Text(s.full) },
        )
    }
}

@Composable
private fun RowDividerOn(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** The signs that mean see a doctor straight away. Shown calmly under every skin result. */
@Composable
fun SafetyNetCard(items: List<String>, modifier: Modifier = Modifier) {
    val c = stepColors(Step.URGENT)
    NilaCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.LocalHospital, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Text("See a doctor straight away if you notice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A guideline question answered Yes / No / Not sure. */
@Composable
fun QuestionCard(q: Question, answer: Answer?, onAnswer: (Answer) -> Unit, aiHint: String? = null) {
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Text(q.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        q.help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        aiHint?.let { InfoNote(it, icon = Icons.Outlined.Visibility, tone = Tone.BLUE) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Answer.YES to "Yes", Answer.NO to "No", Answer.UNSURE to "Not sure").forEach { (a, label) ->
                ChoiceChip(label, selected = answer == a, onClick = { onAnswer(a) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = com.nila.skin.ui.theme.LocalNilaDarkTheme.current
    val fill = if (dark) com.nila.skin.ui.theme.ButtonFillDark else scheme.primary
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) fill else scheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Multi-select chips that wrap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipGroup(options: List<T>, label: (T) -> String, selected: (T) -> Boolean, onToggle: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> ChoiceChip(label(o), selected(o), { onToggle(o) }) }
    }
}

/** A small titled block of plain facts. */
@Composable
fun FactCard(title: String, icon: ImageVector, tone: Tone, lines: List<String>, modifier: Modifier = Modifier, footer: String? = null) {
    NilaCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, tone, size = 36.dp)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        footer?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A line chart for one measure over time, drawn in the brand teal. */
@Composable
fun TrendChart(values: List<Double>, label: String, modifier: Modifier = Modifier, unit: String = "") {
    val tone = toneColors(Tone.TEAL)
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            values.lastOrNull()?.let { Text("%.1f%s".format(it, unit), style = MaterialTheme.typography.labelLarge, color = tone.ink) }
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(tone.wash)) {
            if (values.size < 2) return@Canvas
            val max = values.max().coerceAtLeast(1e-6); val min = minOf(0.0, values.min())
            val span = (max - min).coerceAtLeast(1e-6)
            val pts = values.mapIndexed { i, v ->
                Offset(12f + i * (size.width - 24f) / (values.size - 1), size.height - 10f - ((v - min) / span).toFloat() * (size.height - 20f))
            }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, tone.ink, style = Stroke(width = 5f))
            pts.forEach { drawCircle(tone.ink, 7f, it) }
        }
    }
}

@Composable
fun Gap(h: Int = 4) = Spacer(Modifier.height(h.dp))

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 6.dp))

/** The line under every check: what the app is, and isn't. */
@Composable
fun NotADiagnosis(modifier: Modifier = Modifier) {
    InfoNote(
        "Nila helps you notice, record and decide when to see a doctor. It does not diagnose, and it never replaces your doctor.",
        modifier = modifier, icon = Icons.Outlined.Info,
    )
}

