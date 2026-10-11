package com.nila.skin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.nila.skin.core.rules.Step

/**
 * A next step, resolved into the three colours a card needs.
 *
 * Nila's rule carries over: amber and red mean something only because they
 * are rare. "Keep an eye on it" and "check again tomorrow" are calm and slate,
 * the doctor steps are amber, and "see a doctor now" is a deep plum: clear
 * and distinct, but no red and no alarm, so a parent reads it calmly.
 */
data class StepColors(val accent: Color, val container: Color, val onContainer: Color) {
    val onContainerMuted: Color get() = onContainer.copy(alpha = 0.75f)
}

object StepPalette {
    fun of(step: Step, dark: Boolean): StepColors = if (!dark) when (step) {
        Step.WATCH -> StepColors(SeverityCalm, SeverityCalmBg, OnSeverityLight)
        Step.RECHECK -> StepColors(ToneSlateInk, ToneSlateBg, OnSeverityLight)
        Step.DOCTOR_SOON, Step.DOCTOR_TODAY -> StepColors(SeverityAttention, SeverityAttentionBg, OnSeverityLight)
        Step.URGENT -> StepColors(TonePlumInk, TonePlumBg, OnSeverityLight)
    } else when (step) {
        Step.WATCH -> StepColors(SeverityCalmDark, SeverityCalmBgDark, OnSeverityDark)
        Step.RECHECK -> StepColors(ToneSlateInkDark, ToneSlateBgDark, OnSeverityDark)
        Step.DOCTOR_SOON, Step.DOCTOR_TODAY -> StepColors(SeverityAttentionDark, SeverityAttentionBgDark, OnSeverityDark)
        Step.URGENT -> StepColors(TonePlumInkDark, TonePlumBgDark, OnSeverityDark)
    }
}

@Composable
@ReadOnlyComposable
fun stepColors(step: Step): StepColors = StepPalette.of(step, LocalNilaDarkTheme.current)

@Composable
@ReadOnlyComposable
fun urgentAccent(): Color = stepColors(Step.URGENT).accent
