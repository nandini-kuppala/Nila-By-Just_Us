package com.nila.skin.core.vlm

import com.nila.skin.core.measure.SkinReading

/**
 * Two independent readers of the same photo: the colour maths and the vision
 * model. Neither decides anything, but when they agree the description is
 * more trustworthy, and when they disagree the parent is asked to look again
 * instead of either being believed. Nothing here changes the advice.
 */
object Agreement {

    enum class Level(val title: String) {
        AGREE("The AI and the maths agree"),
        PARTLY("The AI and the maths partly agree"),
        DISAGREE("The AI and the maths disagree"),
        NO_AI("Maths only"),
    }

    data class Result(val level: Level, val points: List<String>)

    private val REDDISH = setOf("pink", "red", "dark red", "purple")
    private val DARKER = setOf("brown", "dark red", "purple")

    fun skin(r: SkinReading, ai: VisionForms.SkinDescription?): Result {
        if (ai == null || ai.notSkin) return Result(Level.NO_AI, emptyList())
        val points = ArrayList<String>()
        var agree = 0; var checked = 0

        ai.colour?.let { c ->
            checked++
            val none = r.coverage < 0.01 || r.spots == 0
            val mathsColours = buildSet {
                if (none) { add("skin-coloured") }
                else {
                    if (r.deltaA >= 4.5) addAll(REDDISH)
                    if (r.deltaL <= -8) addAll(DARKER)
                    if (r.deltaL >= 6) { add("white"); add("pink") }
                    if (r.deltaB >= 7) add("yellow")
                    if (isEmpty()) add("skin-coloured")
                }
            }
            if (c in mathsColours) { agree++; points += "Colour: both see $c." }
            else points += "Colour: the AI says $c; the maths measured ${mathsWord(r)}."
        }
        ai.pattern?.let { p ->
            checked++
            val mathsPattern = when {
                r.spots == 0 || r.coverage < 0.01 -> "none"
                r.coverage > 0.25 && (r.spots == 1 || r.largestSpot >= 0.8 * r.coverage) -> "patch"
                r.spots == 1 -> "single spot"
                r.spots <= 5 -> "few spots"
                else -> "many small spots"
            }
            val near = mapOf(
                "patch" to setOf("patch", "single spot", "ring"),
                "single spot" to setOf("single spot", "patch"),
                "few spots" to setOf("few spots", "many small spots"),
                "many small spots" to setOf("many small spots", "few spots"),
                "none" to setOf("none"),
            )
            if (p in (near[mathsPattern] ?: setOf(mathsPattern))) { agree++; points += "Pattern: both see ${if (p == mathsPattern) p else "$p / $mathsPattern"}." }
            else points += "Pattern: the AI says $p; the maths counted ${r.spots} spot${if (r.spots == 1) "" else "s"}."
        }
        // Texture: the AI's "dry, scaly, crusts, bumps" against the measured roughness.
        val roughWords = setOf(VisionForms.Surface.DRY, VisionForms.Surface.CRUSTS, VisionForms.Surface.RAISED)
        if (ai.surfaces.isNotEmpty()) {
            checked++
            val aiRough = ai.surfaces.any { it in roughWords }
            val mathsRough = r.textureRatio >= com.nila.skin.core.measure.SkinMeasure.ROUGH
            if (aiRough == mathsRough) { agree++; points += if (aiRough) "Texture: both see a rough surface." else "Texture: both see a smooth surface." }
            else points += if (aiRough) "Texture: the AI says ${ai.surfaces.joinToString { it.label.lowercase() }}; the maths found the surface about as smooth as the skin around it."
                           else "Texture: the AI says ${ai.surfaces.joinToString { it.label.lowercase() }}; the maths measured ${"%.1f".format(r.textureRatio)}× the fine detail of the skin around it."
        }
        val level = when {
            checked == 0 -> Level.NO_AI
            agree == checked -> Level.AGREE
            agree * 2 < checked -> Level.DISAGREE
            else -> Level.PARTLY
        }
        return Result(level, points)
    }

    private fun mathsWord(r: SkinReading): String = when {
        r.coverage < 0.01 || r.spots == 0 -> "no clear difference from the skin around it"
        r.deltaA >= 4.5 -> "redder than the skin around it"
        r.deltaL <= -8 -> "darker than the skin around it"
        r.deltaL >= 6 -> "lighter than the skin around it"
        else -> "only a slight difference"
    }
}
