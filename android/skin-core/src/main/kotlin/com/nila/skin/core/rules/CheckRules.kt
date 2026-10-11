package com.nila.skin.core.rules

import com.nila.skin.core.measure.EyeAngle
import com.nila.skin.core.measure.EyePairReading
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.StoolGroup
import com.nila.skin.core.measure.StoolSwatch
import com.nila.skin.core.measure.YellowReading

/**
 * Yellow check for newborns (jaundice).
 *
 * The rows are the WHO/India IMNCI young-infant jaundice classification --
 * any yellow in the first 24 hours, or yellow palms or soles at any age, is
 * "severe jaundice: refer urgently" -- plus the NHS signs that go with it and
 * the 14-day rule for jaundice that lasts.
 */
object JaundiceRules {

    val PALMS = Question("palms", "In daylight, do the palms or the soles of the feet look yellow?",
        "Press gently on the palm or sole for a second and look at the colour as you let go.")
    val EYES = Question("eyes", "Do the whites of the eyes look yellow?")
    val SLEEPY = Question("sleepy", "Is your baby very sleepy, hard to wake, floppy, or not waking for feeds?")
    val STIFF = Question("stiff", "Is your baby stiff, arching their back, or crying in a high-pitched way?")
    val FEEDING = Question("feeding", "Is your baby feeding poorly?")
    val PALE_POO = Question("palepoo", "Is the poo pale (chalky, clay or very light yellow), or the pee dark yellow?")

    val questions = listOf(PALMS, EYES, SLEEPY, STIFF, FEEDING, PALE_POO)

    data class Input(
        val ageHours: Int,
        val answers: Map<String, Answer>,
        val anyYellowSeen: Boolean,
        val readings: List<YellowReading>,
        /** Eye-white yellowness from the previous check, to see a rise. */
        val previousEyeYellowness: Double? = null,
        val preterm: Boolean = false,
    )

    fun advise(input: Input): Advice {
        val a = input.answers
        fun yes(q: Question) = a[q.id] == Answer.YES
        val r = ArrayList<Reason>()
        val yellowSeen = input.anyYellowSeen || yes(PALMS) || yes(EYES)

        if (yellowSeen && input.ageHours < 24) r += Reason("Any yellow colour in the first 24 hours after birth needs a doctor straight away.", Source.WHO_IMNCI, Step.URGENT)
        if (yes(PALMS)) r += Reason("Yellow palms or soles need a doctor straight away (the WHO chart refers these babies the same day).", Source.WHO_IMNCI, Step.URGENT)
        if (yes(SLEEPY) && yellowSeen) r += Reason("A yellow baby who is very sleepy or hard to wake needs a doctor straight away.", Source.NHS_JAUNDICE, Step.URGENT)
        if (yes(STIFF)) r += Reason("Stiffness, arching or a high-pitched cry needs a doctor straight away.", Source.NHS_JAUNDICE, Step.URGENT)
        if (yes(PALE_POO)) r += Reason("Pale poo or dark pee with jaundice can mean a liver problem that is time-critical.", Source.STOOL_CARD, Step.DOCTOR_TODAY)
        if (yes(FEEDING) && yellowSeen) r += Reason("A yellow baby who is feeding poorly should be seen today.", Source.NHS_JAUNDICE, Step.DOCTOR_TODAY)
        val lastingDays = if (input.preterm) 21 else 14
        if (yellowSeen && input.ageHours >= lastingDays * 24)
            r += Reason("Jaundice that is still there after $lastingDays days should be checked, along with the poo colour.", Source.NHS_JAUNDICE, Step.DOCTOR_SOON)

        val eye = input.readings.firstOrNull { it.site == YellowReading.Site.EYE_WHITE && it.whiteCorrected }
        val prev = input.previousEyeYellowness
        if (eye != null && prev != null && eye.yellowness - prev >= 6 && yellowSeen)
            r += Reason("Your colour-corrected eye photos look more yellow than at the last check.", null, Step.DOCTOR_SOON)

        val base = if (yellowSeen) Step.RECHECK else Step.WATCH
        return Advice.of(r, base, ::doNow, listOf(
            "Ask whether a bilirubin test (skin meter or blood) is needed.",
            "Mention the poo and pee colours.",
        ))
    }

    fun doNow(step: Step): List<String> = when (step) {
        Step.URGENT -> listOf(Emergency.LINE, "Jaundice can be treated quickly with light therapy when it's caught early.")
        Step.DOCTOR_TODAY -> listOf("Contact your doctor or the hospital where your baby was born today.")
        Step.DOCTOR_SOON -> listOf("See your doctor within the next day or two.", "Keep a nappy photo for the stool colour check.")
        Step.RECHECK -> listOf("Check again tomorrow in daylight: eyes, palms and soles.", "Feed often; if palms or soles turn yellow, go straight away.")
        Step.WATCH -> listOf("Keep checking in daylight once a day for the first two weeks.")
    }
}

/** Stool colour check (biliary atresia screening card, plus blood). */
object StoolRules {

    data class Input(val chosen: StoolSwatch, val reading: StoolCard.Reading?, val ageDays: Int)

    fun advise(input: Input): Advice {
        val r = ArrayList<Reason>()
        when (input.chosen.group) {
            StoolGroup.PALE -> r += Reason("This colour is on the pale side of the stool card. Pale poo should always be checked by a doctor promptly.", Source.STOOL_CARD, Step.DOCTOR_TODAY)
            StoolGroup.RED -> r += Reason("Red in the nappy can be blood and should be checked today.", Source.NHS_POO, Step.DOCTOR_TODAY)
            StoolGroup.BLACK -> if (input.ageDays > 3)
                r += Reason("Black poo after the first few days can be digested blood and should be checked today.", Source.NHS_POO, Step.DOCTOR_TODAY)
            StoolGroup.NORMAL -> Unit
        }
        val m = input.reading
        if (m != null && input.chosen.group == StoolGroup.NORMAL && m.group == StoolGroup.PALE)
            r += Reason("You picked a normal colour, but the colour-corrected photo reads pale. Compare again by a window; if unsure, ask your doctor.", Source.STOOL_CARD, Step.RECHECK)
        return Advice.of(r, Step.WATCH, ::doNow, listOf("Say \"pale stool\" and ask whether a liver (bilirubin) blood test is needed.").takeIf { input.chosen.group == StoolGroup.PALE } ?: emptyList()) { s ->
            when (s) { Step.WATCH -> "A normal colour"; Step.RECHECK -> "Compare the next nappy in daylight"; else -> null }
        }
    }

    fun doNow(step: Step): List<String> = when (step) {
        Step.URGENT, Step.DOCTOR_TODAY -> listOf("Contact your doctor today and show them this photo.", "Keep the nappy if you can, or take it with you.")
        Step.DOCTOR_SOON -> listOf("Book a doctor visit this week.")
        Step.RECHECK -> listOf("Check the next nappy in daylight next to the card.")
        Step.WATCH -> listOf("Normal colour. For the first month, a quick check each day catches changes early.")
    }
}

/** White-pupil (leukocoria) photo check. */
object EyeRules {

    val TURN = Question("turn", "Does one eye turn in or out, look cloudy, or wobble?")

    data class Input(
        val latest: EyePairReading?,
        /** How many past photos showed a white reflex, per eye. */
        val whiteCountLeft: Int,
        val whiteCountRight: Int,
        val answers: Map<String, Answer>,
        /** Gaze and head turn from face landmarks, when a face was found. */
        val angle: EyeAngle? = null,
    )

    fun advise(input: Input): Advice {
        val r = ArrayList<Reason>()
        val latest = input.latest
        val angle = input.angle
        if (latest != null && angle != null) {
            val offWhite = (latest.left.reflex == Reflex.WHITE && angle.leftOffAxis) || (latest.right.reflex == Reflex.WHITE && angle.rightOffAxis)
            if (offWhite) r += Reason("${angle.words()} in this photo. At that angle the flash can light the optic nerve and glow white in a healthy eye, so a straight-on photo matters.", Source.RED_REFLEX, Step.RECHECK)
        }
        if (input.whiteCountLeft >= 2 || input.whiteCountRight >= 2)
            r += Reason("A white or yellow glow showed in the same eye in more than one photo. Ask for a red reflex eye check.", Source.RED_REFLEX, Step.DOCTOR_SOON)
        else if (latest?.anyWhite == true)
            r += Reason("One photo showed a white glow. This also happens with an off-angle flash in healthy eyes, so take 2-3 more photos.", Source.RED_REFLEX, Step.RECHECK)
        if (latest?.unequal == true && latest.anyWhite.not())
            r += Reason("The two pupils glowed differently. Re-take with the baby looking straight at the phone.", Source.RED_REFLEX, Step.RECHECK)
        if (input.answers[TURN.id] == Answer.YES)
            r += Reason("An eye that turns, looks cloudy or wobbles should be checked.", Source.RED_REFLEX, Step.DOCTOR_SOON)
        return Advice.of(r, Step.WATCH, ::doNow, listOf("Ask for a red reflex test and show the flash photos.")) { s ->
            when (s) { Step.RECHECK -> "Take 2-3 more photos"; Step.WATCH -> "Both eyes look as expected"; Step.DOCTOR_SOON -> "Book an eye check this week"; else -> null }
        }
    }

    fun doNow(step: Step): List<String> = when (step) {
        Step.URGENT, Step.DOCTOR_TODAY, Step.DOCTOR_SOON -> listOf("Book an eye check this week (your doctor can do a red reflex test in a minute).", "Bring the photos: the report includes them.")
        Step.RECHECK -> listOf("In a dim room, flash on, phone about 1 metre away at eye level, baby looking at the phone. Take 3 photos.")
        Step.WATCH -> listOf("Both eyes look as expected in this photo. A flash photo every month or two is plenty.")
    }
}
