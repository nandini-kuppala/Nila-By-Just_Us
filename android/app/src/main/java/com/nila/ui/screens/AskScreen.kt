package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.assistant.Assistant
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.theme.severityColors

// Deliberately mixed between the two people being cared for, because the app
// answers for both and nobody discovers that from an empty text box.
private val SUGGESTIONS = listOf(
    "How much crying is normal?",
    "What can I eat while breastfeeding?",
    "When did she last feed?",
    "Is my milk supply enough?",
    "When should I worry about a fever?",
    "I feel low since the birth",
)

@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Ask",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "About your baby or about you. ${state.knowledgeSize} sourced " +
                "entries, all on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = question,
            onValueChange = state::setDraftQuestion,
            label = { Text("Your question") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state.ask(question) },
                enabled = question.isNotBlank() && !thinking,
                modifier = Modifier.weight(1f),
            ) { Text(if (thinking) "Looking..." else "Ask") }
            // Appears only when there is something to clear, so the button next
            // to Ask is not a permanently greyed-out distraction.
            if (answer != null || question.isNotBlank()) {
                TextButton(onClick = state::clearAnswer) { Text("Clear") }
            }
        }

        if (answer == null && !thinking) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Try one of these")
                // Wrapped rather than one per line. Six full-width chips pushed
                // everything else off the screen and read as a menu of the only
                // things the app could answer.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SUGGESTIONS.forEach { suggestion ->
                        AssistChip(
                            onClick = {
                                state.setDraftQuestion(suggestion)
                                state.ask(suggestion)
                            },
                            label = { Text(suggestion) },
                        )
                    }
                }
            }
        }

        // One wait, naming itself. Retrieval is under a second; a personalised
        // answer means a small model is writing, which is several -- and a
        // spinner that says the same thing for both reads as a hang.
        if (thinking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(
                    when (stage) {
                        AppState.AskStage.WRITING_PERSONAL ->
                            "Reading your records and writing an answer"
                        AppState.AskStage.WRITING_GENERAL ->
                            "Nothing filed on that - answering from what the model knows"
                        else -> "Searching the corpus"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        answer?.let { AnswerCard(it) }
    }
}

@Composable
private fun AnswerCard(answer: Assistant.Answer) {
    // An emergency answer must not look like every other answer. Same weight,
    // same card, same grey text is how someone skims past the one that mattered.
    if (answer.kind == Assistant.Answer.Kind.EMERGENCY) {
        val colors = severityColors(Severity.URGENT)
        Surface(
            color = colors.container,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("GET HELP NOW",
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Bold,
                     letterSpacing = 0.8.sp,
                     color = colors.accent)
                // Was `onSurface` on a hardcoded pale pink, which the dark
                // scheme resolved to near-white -- so the one answer on this
                // screen that must never be missed was the one that vanished.
                Text(answer.text,
                     style = MaterialTheme.typography.bodyLarge,
                     lineHeight = 24.sp,
                     color = colors.onContainer)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            // Just the answer.
            //
            // There used to be a "USING" block here, listing the facts about
            // the baby that had gone into the retrieval -- her age, her feeding
            // notes, her hearing screen. The intent was inspectability, and the
            // effect was that every answer ended in a paragraph of the reader's
            // own health record restated back at them, below the part they
            // asked for. It is the least useful thing on the card and it was
            // the last thing on it.
            AnswerBody(answer.text)
        }

        // What produced these words. One line, and the difference between an
        // answer a reader can weigh and one they cannot: corpus text with a
        // source behind it and a 0.5B model's general knowledge are not the
        // same kind of thing, however similarly they read.
        when (answer.origin) {
            Assistant.Answer.Origin.MODEL_ONLY -> HedgeNote(
                "Nothing in the sourced corpus covered this, so this was written " +
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
        answer.cautions.filter { it.isNotBlank() }.forEach { HedgeNote(it) }

        // The sources stay. They are one line, they are the difference between
        // this and a chatbot, and they are what makes an answer checkable.
        if (answer.sources.isNotEmpty()) {
            Text(
                "Source: ${answer.sources.joinToString(" · ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The answer text, as paragraphs rather than one block.
 *
 * Two things arrive here that a single `Text` renders badly. Retrieved entries
 * are separated by blank lines and ran together into a wall. And the model's
 * output carries line breaks -- sometimes real ones, sometimes the two literal
 * characters backslash-n, which is what put "...at night.\nIf it continues..."
 * on screen. The literal form is converted in `LlmBridge.sanitise`; this is the
 * other half, giving the breaks that survive somewhere to go.
 *
 * A leading "- " becomes a real bullet, because the model produces them despite
 * being asked not to, and a hyphen at the start of a line is not a list.
 */
@Composable
private fun AnswerBody(text: String) {
    val blocks = remember(text) {
        text.replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
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
                                Text("\u2022",
                                     style = MaterialTheme.typography.bodyLarge,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    line.removePrefix("- ").trim(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 24.sp,
                                )
                            }
                        } else {
                            Text(line,
                                 style = MaterialTheme.typography.bodyLarge,
                                 lineHeight = 24.sp)
                        }
                    }
                }
            } else {
                Text(
                    lines.joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                )
            }
        }
    }
}
