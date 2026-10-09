package com.nila.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * A soft identity colour: the pale ground of an icon badge or a tinted tile, and
 * the ink that sits on it.
 *
 * Same discipline as [SeverityColors]. A caller takes the ground and the ink as
 * one object for the theme in force, so no tile can end up with a pale ground in
 * the dark theme or an ink the dark scheme has turned white.
 */
data class ToneColors(val container: Color, val ink: Color) {
    /** The faintest wash of the tone, for a large tile rather than a badge. */
    val wash: Color get() = container.copy(alpha = 0.55f)
}

/**
 * What each tone stands for in the app, so the same thing is the same colour on
 * every screen: feeding is teal on the quick log, the timeline and the health
 * shelf alike.
 */
enum class Tone {
    /** The brand, feeds, all-clear. */
    TEAL,
    /** Nappies, warmth, tips. */
    PEACH,
    /** Sleep, night, devices. */
    BLUE,
    /** Crying. The chart hue, never a warning. */
    PLUM,
    /** Hearts, the mother, allergies. */
    PINK,
    /** Neutral information. */
    SLATE;

    fun colors(dark: Boolean): ToneColors = if (!dark) when (this) {
        TEAL -> ToneColors(ToneTealBg, ToneTealInk)
        PEACH -> ToneColors(TonePeachBg, TonePeachInk)
        BLUE -> ToneColors(ToneBlueBg, ToneBlueInk)
        PLUM -> ToneColors(TonePlumBg, TonePlumInk)
        PINK -> ToneColors(TonePinkBg, TonePinkInk)
        SLATE -> ToneColors(ToneSlateBg, ToneSlateInk)
    } else when (this) {
        TEAL -> ToneColors(ToneTealBgDark, ToneTealInkDark)
        PEACH -> ToneColors(TonePeachBgDark, TonePeachInkDark)
        BLUE -> ToneColors(ToneBlueBgDark, ToneBlueInkDark)
        PLUM -> ToneColors(TonePlumBgDark, TonePlumInkDark)
        PINK -> ToneColors(TonePinkBgDark, TonePinkInkDark)
        SLATE -> ToneColors(ToneSlateBgDark, ToneSlateInkDark)
    }
}

/** [tone] for the theme currently in force. */
@Composable
@ReadOnlyComposable
fun toneColors(tone: Tone): ToneColors = tone.colors(LocalNilaDarkTheme.current)

/** The pale ground the spot illustrations sit on. */
@Composable
@ReadOnlyComposable
fun illustrationGround(): Color =
    if (LocalNilaDarkTheme.current) IllGroundDark else IllGround
