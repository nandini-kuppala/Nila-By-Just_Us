package com.nila.skin.core.rules

import com.nila.skin.core.measure.SkinReading
import com.nila.skin.core.measure.TrendSummary

/** What the parent says they are tracking. Their words, not a diagnosis. */
enum class ConcernKind(val label: String) {
    RASH("Rash or spots"),
    DRY_PATCH("Dry, rough or itchy patch"),
    NAPPY("Nappy area"),
    BIRTHMARK("Birthmark or red mark"),
    OTHER("Something else"),
}

enum class BodyArea(val label: String, val highRiskForBirthmark: Boolean = false) {
    SCALP("Scalp"),
    FACE("Face"),
    NEAR_EYE("Near the eye", true),
    LIP_NOSE("Lip or nose", true),
    NECK("Neck"),
    CHEST_BELLY("Chest or tummy"),
    BACK("Back"),
    ARMS_HANDS("Arms or hands"),
    LEGS_FEET("Legs or feet"),
    NAPPY_AREA("Nappy area", true),
}

/**
 * The rash and skin checklist.
 *
 * Questions are the red and amber features of NICE NG143's traffic-light table
 * that a parent can see, the NHS glass test, NICE CG57's signs of infected
 * eczema, the WHO/IMNCI young-infant skin-infection rows, and the AAP
 * hemangioma risk features. The maths and the AI never answer these; the
 * parent does, and the rule fires on their answer.
 */
object SkinRules {

    val GLASS = Question("glass", "Do the spots stay visible when you press the side of a clear glass firmly on them?",
        "On brown or black skin this is harder to see; check paler areas like the palms, soles or inside the eyelids.")
    val FEVER = Question("fever", "Does your baby have a fever (38°C or higher)?")
    val DROWSY = Question("drowsy", "Is your baby floppy, unusually sleepy, hard to wake, or crying in a weak, high-pitched or non-stop way?")
    val BREATHING = Question("breathing", "Is your baby breathing fast or with effort, grunting, or is their skin blue, grey, very pale or blotchy?")
    val SWELLING = Question("swelling", "Is there swelling of the lips, face, tongue or around the eyes?")
    val FEEDING = Question("feeding", "Is your baby feeding much less than usual, or having far fewer wet nappies?")
    val BLISTERS = Question("blisters", "Are there blisters, pus spots, yellow crusts, weeping skin or open sores?")
    val SPREADING = Question("spreading", "Is it spreading quickly, or getting worse even with care?")
    val WEEK = Question("week", "Has it been there for more than a week?")
    val GROWING = Question("growing", "Has the mark grown or changed since you first noticed it?")
    val SORE = Question("sore", "Is the mark broken, bleeding or sore?")

    fun questions(kind: ConcernKind): List<Question> = when (kind) {
        ConcernKind.BIRTHMARK -> listOf(GROWING, SORE, FEVER, DROWSY)
        else -> listOf(GLASS, FEVER, DROWSY, BREATHING, SWELLING, FEEDING, BLISTERS, SPREADING, WEEK)
    }

    /** Signs that change the advice at once, shown on every skin result (safety-netting). */
    val safetyNet = listOf(
        "Spots that don't fade under a glass",
        "Floppy, very sleepy or hard to wake",
        "Fast or difficult breathing, or blue, grey or blotchy skin",
        "Swelling of the lips, face or tongue",
        "A fever in a baby under 3 months",
    )

    data class Input(
        val kind: ConcernKind,
        val area: BodyArea,
        val ageDays: Int,
        val answers: Map<String, Answer>,
        val reading: SkinReading?,
        val trend: TrendSummary?,
        /** Number of separate birthmarks the parent has logged. */
        val birthmarkCount: Int = 0,
        /** True for the first photo of a new concern. */
        val firstPhoto: Boolean = false,
    )

    fun advise(input: Input): Advice {
        val a = input.answers
        fun yes(q: Question) = a[q.id] == Answer.YES
        fun unsure(q: Question) = a[q.id] == Answer.UNSURE
        val r = ArrayList<Reason>()

        // ---- red: get help now ----------------------------------------------
        if (yes(GLASS)) r += Reason("Spots that don't fade under a glass can be a sign of meningitis or sepsis.", Source.NHS_RASH, Step.URGENT)
        if (yes(DROWSY)) r += Reason("Floppiness, being hard to wake or a weak, high-pitched cry is a red feature.", Source.NICE_NG143, Step.URGENT)
        if (yes(BREATHING)) r += Reason("Breathing difficulty or blue, grey or mottled skin is a red feature.", Source.NICE_NG143, Step.URGENT)
        if (yes(SWELLING)) r += Reason("Swelling of the lips, face or tongue can be a severe allergic reaction.", Source.ANAPHYLAXIS, Step.URGENT)
        if (yes(FEVER) && input.ageDays < 90) r += Reason("Any fever of 38°C or more in a baby under 3 months is a red feature.", Source.NICE_NG143, Step.URGENT)
        if (yes(BLISTERS) && input.ageDays < 60 && (input.reading?.spots ?: 0) >= 10)
            r += Reason("In a baby under 2 months, 10 or more pus spots means a possible serious infection.", Source.WHO_IMNCI, Step.URGENT)

        // ---- amber: today ---------------------------------------------------
        if (yes(FEVER) && input.ageDays >= 90) r += Reason("A rash with a fever needs a doctor to check for infection.", Source.NICE_NG143, Step.DOCTOR_TODAY)
        if (unsure(GLASS) && yes(FEVER)) r += Reason("With a fever, do the glass test again now. If you still can't tell, treat it as not fading.", Source.NHS_RASH, Step.DOCTOR_TODAY)
        if (yes(FEEDING)) r += Reason("Feeding much less or fewer wet nappies is an amber feature.", Source.NICE_NG143, Step.DOCTOR_TODAY)
        if (yes(BLISTERS) && input.kind != ConcernKind.BIRTHMARK && r.none { it.source == Source.WHO_IMNCI })
            r += Reason("Blisters, pus, crusts or weeping can mean the skin is infected and may need treatment.", Source.NICE_CG57, Step.DOCTOR_TODAY)
        if (yes(SORE)) r += Reason("A birthmark that is broken, bleeding or sore needs to be looked at.", Source.AAP_HEMANGIOMA, Step.DOCTOR_TODAY)

        // ---- this week ------------------------------------------------------
        if (yes(SPREADING)) r += Reason("You said it is spreading or getting worse despite care.", Source.NHS_RASH, Step.DOCTOR_SOON)
        if (yes(WEEK)) r += Reason("It has lasted more than a week.", Source.NHS_RASH, Step.DOCTOR_SOON)
        if (input.trend?.spreadingFast == true) r += Reason("Your photos show it at least half as big again within a few days.", null, Step.DOCTOR_SOON)
        if (input.kind == ConcernKind.BIRTHMARK) {
            val growing = yes(GROWING) || input.trend?.size == TrendSummary.Direction.BIGGER
            if (growing && input.ageDays < 90) r += Reason("A birthmark that changes in the first months should be seen early; photos help the doctor.", Source.AAP_HEMANGIOMA, Step.DOCTOR_SOON)
            if (input.area.highRiskForBirthmark) r += Reason("Marks near the eye, on the lip or nose, or in the nappy area are best checked early.", Source.AAP_HEMANGIOMA, Step.DOCTOR_SOON)
            if (input.birthmarkCount >= 5) r += Reason("Five or more of these marks is worth telling the doctor about.", Source.AAP_HEMANGIOMA, Step.DOCTOR_SOON)
        }

        val base = if (input.firstPhoto || input.trend == null) Step.RECHECK else Step.WATCH
        return Advice.of(r, base, ::doNow, askDoctor(input))
    }

    fun doNow(step: Step): List<String> = when (step) {
        Step.URGENT -> listOf(Emergency.LINE, "Take your phone with you: the photos and your answers are in the doctor report.")
        Step.DOCTOR_TODAY -> listOf("Call your doctor or visit a clinic today.", "Show them the photos: tap Doctor report to share everything in one page.")
        Step.DOCTOR_SOON -> listOf("Book a visit this week.", "Keep taking one photo a day of the same spot, so the doctor can see the change.")
        Step.RECHECK -> listOf("Take another photo of the same spot tomorrow, in daylight, from the same distance.", "If any of the warning signs below appear, get help straight away.")
        Step.WATCH -> listOf("Keep following the care you've been given.", "Take a photo every few days, or sooner if it changes.")
    }

    private fun askDoctor(input: Input): List<String> = buildList {
        add("When it started and how it has changed (the photo timeline in the report).")
        if (input.kind == ConcernKind.BIRTHMARK) add("Whether this mark needs treatment, and by what age.")
        else add("What it is, and how to care for the skin at home.")
        add("Which signs should make you come back sooner.")
    }
}
