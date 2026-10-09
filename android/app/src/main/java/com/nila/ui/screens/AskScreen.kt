package com.nila.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Send

import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.assistant.Assistant
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.components.AskBabyArt
import com.nila.ui.components.CardShape
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.IconBadge
import com.nila.ui.components.NilaCard
import com.nila.ui.components.PillButton
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SectionTitle
import com.nila.ui.theme.Tone
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors

/**
 * A suggested question, and the badge it wears.
 *
 * Deliberately mixed between the two people being cared for, because the app
 * answers for both and nobody discovers that from an empty text box.
 */
private data class Suggestion(val text: String, val icon: ImageVector, val tone: Tone)

private val SUGGESTIONS = listOf(
    Suggestion("How much crying is normal?", Icons.Outlined.SentimentDissatisfied, Tone.PLUM),
    Suggestion("What can I eat while breastfeeding?", Icons.Outlined.Restaurant, Tone.TEAL),
    Suggestion("When did she last feed?", Icons.Outlined.Schedule, Tone.BLUE),
    Suggestion("Is my milk supply enough?", Icons.Outlined.WaterDrop, Tone.BLUE),
    Suggestion("When should I worry about a fever?", Icons.Outlined.Thermostat, Tone.PEACH),
    Suggestion("I feel low since the birth", Icons.Outlined.FavoriteBorder, Tone.PINK),
)

/**
 * Ask: an empty box with good company, and then an answer with its sources.
 *
 * Two states, one screen. Before a question it is an invitation -- six real
 * questions people ask, and a plain statement of where the answers come from,
 * which is the thing that makes this different from a chatbot. After one, it is
 * the answer and nothing else competing with it, with a follow-up box at the
 * bottom so the next question does not mean starting over.
 */
@Composable
fun AskScreen(state: AppState) {
    val question by state.draftQuestion.collectAsState()
    val answer by state.answer.collectAsState()
    val thinking by state.thinking.collectAsState()
    val stage by state.asking.collectAsState()

    /**
     * Leaving the screen puts it back to an empty box.
     *
     * The question and the answer both live in the view model, which was the
     * fix for an earlier bug where only one of them survived navigation and the
     * two fell out of step. Keeping both was the wrong resolution: coming back
     * to Ask meant reading somebody's previous question, and the first thing
     * anybody did was clear it by hand. Clearing both together fixes the
     * original inconsistency in the other direction, and the exchange is
     * already in the timeline.
     */
    DisposableEffect(Unit) {
        onDispose { state.clearAnswer() }
    }

    val conversing = answer != null || thinking
    // Back from an answer returns to the empty box, not out of the tab.
    BackHandler(enabled = conversing && !thinking) { state.clearAnswer() }

    fun ask(text: String) {
        if (text.isBlank() || thinking) return
        state.setDraftQuestion(text)
        state.ask(text)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!conversing) {
            ScreenHero(
                title = "Ask anything",
                body = "Sourced answers about your baby or about you. " +
                    "Everything stays on this phone.",
                art = { AskBabyArt() },
            )

            QuestionBox(
                value = question,
                onValueChange = state::setDraftQuestion,
                onSubmit = { ask(question) },
            )
            PillButton(
                "Ask",
                onClick = { ask(question) },
                enabled = question.isNotBlank() && !thinking,
                icon = Icons.AutoMirrored.Outlined.Send,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Try one of these")
            SUGGESTIONS.chunked(2).forEach { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(IntrinsicSize.Max),
                ) {
                    pair.forEach { s ->
                        SuggestionCard(s, Modifier.weight(1f).fillMaxHeight()) { ask(s.text) }
                    }
                }
            }

            SourcesCard(state.knowledgeSize)
        } else {
            QuestionBar(
                question = question,
                onBack = state::clearAnswer,
                onEdit = {
                    val q = question
                    state.clearAnswer()
                    state.setDraftQuestion(q)
                },
                enabled = !thinking,
            )

            // One wait, naming itself. Retrieval is under a second; a
            // personalised answer means a small model is writing, which is
            // several -- and a spinner that says the same thing for both reads
            // as a hang.
            if (thinking) {
                NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp),
                                                  strokeWidth = 2.5.dp)
                        Text(
                            when (stage) {
                                AppState.AskStage.WRITING_PERSONAL ->
                                    "Reading your records and writing an answer"
                                AppState.AskStage.WRITING_GENERAL ->
                                    "Nothing filed on that – answering from what the model knows"
                                else -> "Searching the sourced entries"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            answer?.let { a ->
                AnswerCard(a)
                if (!thinking) {
                    FollowUps(
                        current = question,
                        onAsk = { ask(it) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- input

/** A borderless multi-line field inside a card: the box to type into. */
@Composable
private fun QuestionBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Ask a question about your baby or you...") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, modifier: Modifier, onClick: () -> Unit) {
    NilaCard(
        modifier = modifier,
        tone = s.tone,
        onClick = onClick,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBadge(s.icon, s.tone, size = 36.dp, iconSize = 20.dp)
            Text(s.text, style = MaterialTheme.typography.bodySmall,
                 fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Where the answers come from, said before anybody asks. It is the one sentence
 * that separates this screen from a chatbot, so it is on the screen rather than
 * in a help page.
 */
@Composable
private fun SourcesCard(entries: Int) {
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(Icons.AutoMirrored.Outlined.MenuBook, Tone.TEAL)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Where the answers come from", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text(
                    "Written entries from the NHS, WHO, LactMed and the AAP, each " +
                        "with its source. $entries entries, all on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --------------------------------------------------------------- answer

/** The question being answered, with the two ways out of it. */
@Composable
private fun QuestionBar(
    question: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = enabled) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "New question")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(scheme.surfaceContainerLow)
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .then(if (enabled) Modifier.clickable(onClick = onEdit) else Modifier)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(question, style = MaterialTheme.typography.bodyLarge,
                 maxLines = 2, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Edit, contentDescription = "Edit the question",
                 tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AnswerCard(answer: Assistant.Answer) {
    // An emergency answer must not look like every other answer. Same weight,
    // same card, same grey text is how someone skims past the one that mattered.
    if (answer.kind == Assistant.Answer.Kind.EMERGENCY) {
        val colors = severityColors(Severity.URGENT)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(colors.container)
                .border(1.5.dp, colors.accent.copy(alpha = 0.5f), CardShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.accent)
                Text("GET HELP NOW",
                     style = MaterialTheme.typography.labelLarge,
                     fontWeight = FontWeight.Bold,
                     letterSpacing = 0.8.sp,
                     color = colors.accent)
            }
            Text(answer.text,
                 style = MaterialTheme.typography.bodyLarge,
                 lineHeight = 24.sp,
                 color = colors.onContainer)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.AutoMirrored.Outlined.MenuBook, Tone.TEAL, size = 40.dp, iconSize = 21.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp,
                                             bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(toneColors(Tone.TEAL).wash)
                    .padding(16.dp),
            ) {
                // Just the answer. There used to be a "USING" block here listing
                // the facts about the baby that went into retrieval; it restated
                // the reader's own record back at them below the part they asked
                // for, and it has gone.
                AnswerBody(answer.text)
            }
        }

        // What produced these words. One line, and the difference between an
        // answer a reader can weigh and one they cannot.
        when (answer.origin) {
            Assistant.Answer.Origin.MODEL_ONLY -> HedgeNote(
                "Nothing in the sourced entries covered this, so this was written " +
                    "on this phone by the small local model from general knowledge " +
                    "and what it knows about you. Treat it as a starting point, not " +
                    "as a sourced answer, and check anything that matters with your " +
                    "doctor."
            )
            Assistant.Answer.Origin.CORPUS_PERSONALISED -> HedgeNote(
                "Written on this phone from the sources below and your own records. " +
                    "The sources are unchanged; the wording is not."
            )
            else -> Unit
        }

        // Every retrieved document carries its own caution, so an answer cannot
        // be assembled without the limit that belongs to it.
        val cautions = answer.cautions.filter { it.isNotBlank() }
        if (cautions.isNotEmpty()) {
            val colors = severityColors(Severity.ATTENTION)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(colors.container)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null,
                         tint = colors.accent, modifier = Modifier.size(22.dp))
                    Text("Worth knowing", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.Bold, color = colors.accent)
                }
                cautions.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         color = colors.onContainer)
                }
            }
        }

        // The sources stay. They are what makes an answer checkable.
        if (answer.sources.isNotEmpty()) SourcesRow(answer.sources)
    }
}

/**
 * The sources, in view. Up to three are listed outright -- the screen promises
 * sourced answers, and a source hidden behind a tap is one nobody reads. A
 * longer list folds.
 */
@Composable
private fun SourcesRow(sources: List<String>) {
    var open by remember(sources) { mutableStateOf(sources.size <= 3) }
    val scheme = MaterialTheme.colorScheme
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp),
             verticalSpacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null,
                 tint = scheme.primary, modifier = Modifier.size(20.dp))
            Text("Sources (${sources.size})", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                 contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape)
                                .background(scheme.primary))
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                             color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * What to ask next: the other suggestions as chips, then a box for anything else.
 * A follow-up should not mean going back to an empty screen.
 */
@Composable
private fun FollowUps(current: String, onAsk: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SUGGESTIONS.filter { it.text != current }.forEach { s ->
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(toneColors(Tone.TEAL).container)
                        .clickable { onAsk(s.text) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(s.text, style = MaterialTheme.typography.labelLarge,
                         color = toneColors(Tone.TEAL).ink)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.surfaceContainerLowest)
                .border(1.dp, scheme.outlineVariant, CircleShape)
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Ask another question...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onAsk(typed); typed = "" }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (typed.isNotBlank()) scheme.primary else scheme.surfaceContainer)
                    .clickable(enabled = typed.isNotBlank()) { onAsk(typed); typed = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Ask",
                     tint = if (typed.isNotBlank()) scheme.onPrimary else scheme.onSurfaceVariant,
                     modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The answer text, as paragraphs rather than one block.
 *
 * Two things arrive here that a single `Text` renders badly. Retrieved entries
 * are separated by blank lines and ran together into a wall. And the model's
 * output carries line breaks -- sometimes real ones, sometimes the two literal
 * characters backslash-n. The literal form is converted in `LlmBridge.sanitise`;
 * this is the other half, giving the breaks that survive somewhere to go.
 *
 * A leading "- " becomes a real bullet, because the model produces them despite
 * being asked not to, and a hyphen at the start of a line is not a list.
 *
 * The first sentence is set in bold when there is more than one: it is almost
 * always the direct answer, and the rest is the reasoning behind it.
 */
@Composable
private fun AnswerBody(text: String) {
    val blocks = remember(text) {
        text.replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // A block with any bullet in it is drawn line by line, so a lead-in
            // sentence above a list keeps the list. A block with none is one
            // paragraph: single breaks inside it are the model wrapping, not
            // structure, and joining them lets it reflow to the width it gets.
            if (lines.any { it.startsWith("- ") }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line ->
                        if (line.startsWith("- ")) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape)
                                        .background(scheme.primary))
                                Text(
                                    line.removePrefix("- ").trim(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 24.sp,
                                )
                            }
                        } else {
                            Text(line, style = MaterialTheme.typography.bodyLarge,
                                 lineHeight = 24.sp)
                        }
                    }
                }
            } else {
                val paragraph = lines.joinToString(" ")
                val lead = if (index == 0) leadSentence(paragraph) else null
                if (lead != null) {
                    Text(lead.first, style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                    Text(lead.second, style = MaterialTheme.typography.bodyLarge,
                         lineHeight = 24.sp)
                } else {
                    Text(paragraph, style = MaterialTheme.typography.bodyLarge,
                         lineHeight = 24.sp)
                }
            }
        }
    }
}

/** The first sentence and the rest, when there is a rest worth separating. */
private fun leadSentence(paragraph: String): Pair<String, String>? {
    val end = Regex("""(?<=[.!?])\s+(?=[A-Z])""").find(paragraph) ?: return null
    val first = paragraph.substring(0, end.range.first).trim()
    val rest = paragraph.substring(end.range.last + 1).trim()
    if (first.length < 20 || first.length > 180 || rest.length < 20) return null
    return first to rest
}
