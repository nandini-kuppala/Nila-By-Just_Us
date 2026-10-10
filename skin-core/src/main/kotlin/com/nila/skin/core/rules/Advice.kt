package com.nila.skin.core.rules

/**
 * What to do next, and nothing else.
 *
 * The app never names a disease. Every screen ends in one of these steps, and
 * every step above "watch" is triggered by a rule taken from a published,
 * parent- or health-worker-facing guideline, cited on screen. The AI and the
 * maths can *describe* and *measure*; they can raise "take another photo" to
 * "show your doctor", but only a checklist answer from a guideline can say
 * "get help now".
 */
enum class Step(val title: String, val short: String) {
    WATCH("Fine to keep an eye on", "Keep an eye on it"),
    RECHECK("Check again tomorrow", "Check again tomorrow"),
    DOCTOR_SOON("Show your doctor this week", "Doctor this week"),
    DOCTOR_TODAY("Talk to a doctor today", "Doctor today"),
    URGENT("Get medical help now", "Help now"),
}

/** Where a rule comes from. Shown under every piece of advice. */
enum class Source(val short: String, val full: String) {
    NICE_NG143("NICE NG143", "NICE guideline NG143, Fever in under 5s: traffic light system for identifying risk of serious illness (2019, updated 2021)"),
    NHS_RASH("NHS rashes & glass test", "NHS: Rashes in babies and children; the glass (tumbler) test for meningitis and sepsis"),
    NICE_CG57("NICE CG57", "NICE guideline CG57, Atopic eczema in under 12s: infected eczema and eczema herpeticum"),
    WHO_IMNCI("WHO / India IMNCI", "WHO & Government of India IMNCI chart booklet, young infant 0-2 months: jaundice and skin infection"),
    NHS_JAUNDICE("NHS newborn jaundice", "NHS: Newborn jaundice symptoms; check eyes, palms, soles and gums in daylight; jaundice beyond 14 days"),
    STOOL_CARD("Infant stool colour card", "Newborn Screening Ontario infant stool colour card (2024); Taiwan national stool card programme (Lien et al., Hepatology 2011)"),
    NHS_POO("NHS baby poo guide", "NHS: Your baby's nappy -- after the first few days, red or black poo, or white/pale poo, needs checking by a doctor"),
    AAP_HEMANGIOMA("AAP 2019 hemangioma guideline", "American Academy of Pediatrics clinical practice guideline for infantile hemangiomas (Krowchuk et al., Pediatrics 2019)"),
    RED_REFLEX("Red reflex check", "AAP: Red reflex examination in neonates, infants and children; CRADLE white-eye study (Munson et al., Science Advances 2019)"),
    NIAID_2017("NIAID 2017 peanut addendum", "NIAID Addendum guidelines for the prevention of peanut allergy (2017), based on the LEAP trial"),
    ANAPHYLAXIS("NHS / Resus Council anaphylaxis", "NHS and Resuscitation Council UK: signs of anaphylaxis in babies -- swelling of lips/face/tongue, breathing difficulty, pale or floppy"),
    ICMR_NIN_2020("ICMR-NIN 2020", "ICMR-National Institute of Nutrition: Nutrient requirements for Indians, RDA and EAR (2020)"),
    WHO_MDD("WHO/UNICEF dietary diversity", "WHO & UNICEF (2021) Indicators for assessing infant and young child feeding: minimum dietary diversity, 5 of 8 food groups"),
    WHO_CF("WHO complementary feeding", "PAHO/WHO Guiding principles for complementary feeding of the breastfed child (2003): energy needed from food"),
    NHS_WEANING("NHS weaning advice", "NHS Start for Life: foods to avoid giving babies and young children (honey under 12 months, whole nuts, whole grapes, salt, cow's milk as a drink)"),
    USDA_FDC("USDA FoodData Central", "USDA FoodData Central, SR Legacy (2018): nutrient values per 100 g"),
}

data class Reason(val text: String, val source: Source?, val step: Step)

/**
 * The outcome of one check: the step, why, and what to say to the doctor.
 */
data class Advice(
    val step: Step,
    val reasons: List<Reason>,
    val doNow: List<String>,
    val askDoctor: List<String> = emptyList(),
) {
    val sources: List<Source> get() = reasons.mapNotNull { it.source }.distinct()

    companion object {
        /** The highest step any reason reached decides; every reason is kept. */
        fun of(reasons: List<Reason>, base: Step, doNow: (Step) -> List<String>, askDoctor: List<String> = emptyList()): Advice {
            val step = (reasons.map { it.step } + base).maxBy { it.ordinal }
            return Advice(step, reasons.sortedByDescending { it.step.ordinal }, doNow(step), askDoctor)
        }
    }
}

/** A yes / no / not-sure question from a guideline checklist. */
enum class Answer { YES, NO, UNSURE }

data class Question(val id: String, val text: String, val help: String? = null)

/** India's emergency numbers, shown with every "help now". */
object Emergency {
    const val NUMBER = "112"
    const val AMBULANCE = "108"
    const val LINE = "Call $NUMBER or $AMBULANCE, or go to the nearest hospital emergency department."
}
