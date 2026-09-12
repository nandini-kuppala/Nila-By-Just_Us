package com.nila.assistant.agents

import com.nila.assistant.KnowledgeDoc
import com.nila.assistant.QueryRouter
import com.nila.assistant.KnowledgeIndex
import com.nila.data.HealthRecord
import com.nila.data.HealthRecordDao
import com.nila.data.MotherProfile
import com.nila.data.RecordSubject

/**
 * Four specialists and an adjudicator, deciding whether a medicine is safe for
 * a specific breastfeeding mother.
 *
 * Why a pipeline rather than one prompt: each stage has a different failure mode
 * and a different evidence source, and keeping them apart means the UI can show
 * *which* stage was uncertain. "I read the strip confidently but I know nothing
 * about your kidney function" is a far more useful answer than a single
 * confidence number over the whole question.
 *
 * The stages are rule-based and run entirely offline. The language model, when
 * one is installed, writes the final summary from the findings the stages
 * produced -- it never contributes a fact of its own. That ordering is the point:
 * a 0.5B model must not be the thing that decides whether a drug is safe.
 */

/** One stage's output. Shown to the user, so it must be readable, not internal. */
data class AgentStep(
    val agent: String,
    val looked_at: String,
    val finding: String,
    val confidence: Confidence,
    val couldNotCheck: String? = null,
    val flags: List<String> = emptyList(),
) {
    enum class Confidence { HIGH, MEDIUM, LOW, NONE }
}

enum class Verdict(val label: String, val severity: Int) {
    LIKELY_SAFE("Likely safe for you", 0),
    CAUTION("Check with your doctor", 1),
    AVOID("Best avoided", 2),
    UNKNOWN("Not enough information", 3),
}

data class MedicineReviewResult(
    val verdict: Verdict,
    val headline: String,
    val steps: List<AgentStep>,
    val medicine: KnowledgeDoc?,
    val sources: List<String>,
    val cautions: List<String>,
    val summary: String,
    /** Always false. Nothing in this app generates health text. */
    val summaryFromLlm: Boolean,
    /**
     * What to call this in a list: what was on the packet, not what we decided
     * it contained.
     *
     * Both halves of a combination, so a history row does not say "Paracetamol"
     * about a tablet that also had an NSAID in it -- and the *brand* when the
     * ingredient was guessed from the brand name, because filing "Oxidol-P" as
     * "Paracetamol" turns a hedged inference into a flat claim the moment it
     * reaches a list somebody scrolls a week later.
     */
    val displayName: String = medicine?.generic.orEmpty(),
)

// ---------------------------------------------------------------- 1. history
/**
 * Reads the mother's profile and her stored records, and reports what is known
 * about her that could change a medicine answer.
 */
class HistoryAgent(private val records: HealthRecordDao) {

    data class MotherContext(
        val conditions: List<String>,
        val allergies: List<String>,
        val currentMedicines: List<String>,
        val breastfeeding: Boolean,
        val weeksPostpartum: Int?,
        val recordsConsulted: Int,
        val fromRecords: List<String>,
    )

    suspend fun run(profile: MotherProfile?): Pair<MotherContext, AgentStep> {
        val stored = records.listForSubject(RecordSubject.MOTHER.name)

        val declared = profile?.conditionList.orEmpty()
        val mentioned = mineConditions(stored, declared)

        val context = MotherContext(
            conditions = (declared + mentioned).distinct(),
            allergies = profile?.allergyList.orEmpty(),
            currentMedicines = profile?.medicineList.orEmpty(),
            breastfeeding = profile?.isBreastfeeding ?: true,
            weeksPostpartum = profile?.weeksPostpartum,
            recordsConsulted = stored.size,
            fromRecords = mentioned,
        )

        val known = declared.size + context.allergies.size +
            context.currentMedicines.size + mentioned.size
        val step = AgentStep(
            agent = "History",
            looked_at = "Your profile and ${stored.size} stored record" +
                if (stored.size == 1) "" else "s",
            finding = when {
                known == 0 && stored.isEmpty() ->
                    "Nothing on file yet. I can only give you the general answer."
                known == 0 ->
                    "Read ${stored.size} records but found no conditions, " +
                        "allergies or current medicines to weigh."
                else -> buildString {
                    // Declared and inferred are reported separately. What she
                    // told us and what we guessed from a scanned page do not
                    // carry the same weight, and merging them hides which is
                    // which at exactly the moment that matters.
                    if (declared.isNotEmpty())
                        append("Conditions: ${declared.joinToString(", ")}. ")
                    if (context.allergies.isNotEmpty())
                        append("Allergies: ${context.allergies.joinToString(", ")}. ")
                    if (context.currentMedicines.isNotEmpty())
                        append("Already taking: " +
                            "${context.currentMedicines.joinToString(", ")}. ")
                    if (mentioned.isNotEmpty())
                        append("Also mentioned in your records: " +
                            "${mentioned.joinToString(", ")}.")
                }.trim()
            },
            confidence = when {
                known > 0 -> AgentStep.Confidence.HIGH
                stored.isNotEmpty() -> AgentStep.Confidence.MEDIUM
                else -> AgentStep.Confidence.NONE
            },
            couldNotCheck = if (known == 0)
                "Add your conditions, allergies and current medicines in Health " +
                    "so this check means something" else null,
            flags = context.fromRecords.map { "found \"$it\" in your records" },
        )
        return context to step
    }

    companion object {
        /** Terms worth spotting in OCR text because they change drug answers. */
        // Specific phrasings are listed alongside their broader forms on
        // purpose: matching both is what lets the subsumption filter drop the
        // vague one. A finding that reads "iron deficiency anaemia, anaemia"
        // looks padded, and padding makes a safety result less believable.
        private val WATCHED_TERMS = listOf(
            "asthma", "type 2 diabetes", "type 1 diabetes", "gestational diabetes",
            "diabetes", "hypertension", "thyroid", "epilepsy",
            "chronic kidney disease", "kidney disease", "liver disease",
            "iron deficiency anaemia", "iron deficiency anemia", "anaemia", "anemia",
            "peptic ulcer", "ulcer", "penicillin allergy", "migraine",
            "postnatal depression", "depression", "gastritis",
        )

        /**
         * Phrasing that means a document is *ruling out* a condition, not
         * recording it.
         *
         * Without this the naive scan reads "EDINBURGH POSTNATAL DEPRESSION
         * SCALE - below referral threshold" and concludes she has depression.
         * A screening someone passed is the single most common way a keyword
         * scan invents a diagnosis, and telling a clinician she has a condition
         * a document explicitly excluded is worse than missing it.
         */
        private val NEGATING = listOf(
            "below referral", "below threshold", "no evidence", "not indicated",
            "ruled out", "negative", "screening", "screen", "scale", "denies",
            "no history", "nil", "within normal", "pass", "unremarkable",
            "questionnaire", "assessment tool", "score",
        )

        /** Characters either side of a match to inspect for negation. */
        private const val WINDOW = 90

        /**
         * Terms mentioned in documents, minus anything a document is denying
         * and anything the caregiver already declared herself.
         */
        internal fun mineConditions(
            records: List<HealthRecord>,
            declared: List<String>,
        ): List<String> {
            val found = mutableListOf<String>()
            for (record in records) {
                val text = record.searchable.lowercase()
                for (term in WATCHED_TERMS) {
                    var from = text.indexOf(term)
                    while (from >= 0) {
                        val start = (from - WINDOW).coerceAtLeast(0)
                        val end = (from + term.length + WINDOW).coerceAtMost(text.length)
                        val around = text.substring(start, end)
                        if (NEGATING.none { around.contains(it) }) {
                            found += term
                            break
                        }
                        from = text.indexOf(term, from + term.length)
                    }
                }
            }
            // Drop anything already stated, and anything subsumed by a more
            // specific phrase -- "anaemia" beside "iron deficiency anaemia" is
            // noise that makes the finding look padded.
            val all = declared.map { it.lowercase() }
            return found.distinct().filterNot { term ->
                all.any { it.contains(term) } ||
                    found.any { it != term && it.contains(term) }
            }
        }
    }
}

// ------------------------------------------------------------- 2. identify
/** Turns noisy OCR text into the medicines on the packet, or admits it could not. */
class IdentifyAgent(private val index: KnowledgeIndex) {

    /**
     * What the packet turned out to be.
     *
     * [docs] rather than one document because most of what an Indian pharmacy
     * sells is a combination, and answering about one of two actives is how a
     * strip with an NSAID on it gets reported as "likely safe" on the strength
     * of the paracetamol beside it.
     *
     * [inferred] marks a composition that was *guessed from the brand name*
     * rather than read. A guess is allowed to raise concern and never to lower
     * it -- see [AdjudicatorAgent].
     */
    data class Identified(
        val docs: List<KnowledgeDoc>,
        val evidence: String,
        val inferred: Boolean = false,
        val brand: String? = null,
    ) {
        val doc: KnowledgeDoc? get() = docs.firstOrNull()
    }

    fun run(ocrText: String): Pair<Identified, AgentStep> {
        val cleaned = ocrText.replace(Regex("\\s+"), " ").trim()
        val looked = if (cleaned.isBlank()) "No readable text"
            else "\"${cleaned.take(90)}${if (cleaned.length > 90) "..." else ""}\""

        // Not everything typed into a medicine box is a medicine. Saying so is
        // better than running four stages on "Hii" and presenting the result as
        // though a check happened.
        if (cleaned.length < 3 || QueryRouter.route(cleaned) is QueryRouter.Route.Greeting) {
            return Identified(emptyList(), cleaned) to AgentStep(
                agent = "Identify",
                looked_at = if (cleaned.isBlank()) "Nothing entered" else "\"$cleaned\"",
                finding = "That does not look like a medicine name.",
                confidence = AgentStep.Confidence.NONE,
                couldNotCheck = "Type the name printed on the strip, or photograph it.",
            )
        }

        // Three, which covers the combinations actually sold -- two actives is
        // the norm and three is the ceiling. Asking for more turns a
        // photograph of a pharmacy shelf into a four-ingredient tablet.
        val hits = index.findMedicines(cleaned, limit = 3)
        if (hits.isNotEmpty()) {
            val docs = hits.map { it.doc }
            val exact = exactlyNamed(docs, cleaned)
            val names = docs.joinToString(" and ") { it.generic }
            return Identified(docs, cleaned) to AgentStep(
                agent = "Identify",
                looked_at = looked,
                finding = when {
                    // Deliberately not "a combination". Two names in one
                    // photograph is usually one tablet with two actives and
                    // occasionally two packets in shot, and this stage cannot
                    // tell which. Both are checked either way, so the wording
                    // says what was read rather than what it was read off.
                    exact && docs.size > 1 ->
                        "Read $names. Both are checked below; if they are " +
                            "separate packets, photograph them one at a time."
                    exact -> "Recognised $names."
                    // Say so when a spelling was corrected. Silently answering
                    // about a different drug than the one someone typed is the
                    // worst possible way to be helpful.
                    else -> "Read this as $names. If that is not what you have, " +
                        "check the spelling on the packet."
                },
                confidence = when {
                    exact && hits.first().score >= 10 -> AgentStep.Confidence.HIGH
                    exact -> AgentStep.Confidence.MEDIUM
                    else -> AgentStep.Confidence.LOW
                },
                flags = buildList {
                    if (!exact) add("spelling corrected")
                    if (docs.size > 1) add("combination product")
                },
            )
        }

        // Nothing in the text is a medicine this app knows. Before giving up,
        // read the brand the way a pharmacist reads it.
        val lead = BrandSuffix.infer(cleaned, index)
        val brand = BrandSuffix.brandIn(cleaned)
        if (lead != null) {
            return Identified(listOf(lead.doc), cleaned, inferred = true, brand = lead.brand) to
                AgentStep(
                    agent = "Identify",
                    looked_at = looked,
                    finding = "I do not know the brand \"${lead.brand}\". Indian brands " +
                        "ending \"-${lead.suffix.uppercase()}\" almost always contain " +
                        "${lead.doc.generic.lowercase()}, so I have checked that on the " +
                        "assumption this one does.",
                    confidence = AgentStep.Confidence.LOW,
                    couldNotCheck = "This is read off the brand name, not off the " +
                        "composition. Check the small print for what is actually in it.",
                    flags = listOf("ingredient inferred from the brand name"),
                )
        }

        return Identified(emptyList(), cleaned, brand = brand) to AgentStep(
            agent = "Identify",
            looked_at = looked,
            finding = if (brand != null)
                "I read the brand \"$brand\" but I do not know that brand."
            else "Could not match anything in the medicine list.",
            confidence = AgentStep.Confidence.NONE,
            couldNotCheck = if (brand != null)
                "Type the generic name instead -- it is the smaller line under the " +
                    "brand, usually ending in -ol, -en, -cin or -zole."
            else "Only ${index.medicineCount} common medicines are covered offline. " +
                "Check the spelling, or ask your pharmacist.",
        )
    }

    /** Did the text contain a name verbatim, or did fuzzy matching rescue it? */
    private fun exactlyNamed(docs: List<KnowledgeDoc>, text: String): Boolean {
        val tokens = KnowledgeIndex.tokenize(text).toSet()
        return docs.any { doc ->
            val names = KnowledgeIndex
                .tokenize(doc.generic + " " + doc.aliases.joinToString(" ")).toSet()
            tokens.intersect(names).isNotEmpty()
        }
    }
}

/**
 * Reading an Indian brand name the way a pharmacist reads it.
 *
 * Brands here are built by suffix, and the suffix is the composition: Zerodol-P,
 * Aceclo-P, Nimulid-P and Oxidol-P all carry paracetamol, and the letter is why
 * the pharmacist reaches for the right box without reading the small print.
 *
 * This is a *guess*, and it is treated as one everywhere it is used. It exists
 * because the alternative -- what the app did before -- was to read "Oxidol-P"
 * off a strip perfectly, recognise nothing, and return "I could not check this
 * one". A hedged partial answer beats a blank one, provided the hedge survives
 * all the way to the screen.
 *
 * Only suffixes that map to a single ingredient are listed. "-MR" is a muscle
 * relaxant that could be any of four; "-LB" is a probiotic; neither belongs
 * here, because a guess with two answers is not a guess, it is a coin toss.
 */
internal object BrandSuffix {

    data class Lead(val doc: KnowledgeDoc, val brand: String, val suffix: String)

    /** Suffix -> the generic it reliably signals. */
    private val MEANS = mapOf(
        "p" to "Paracetamol",
        "cv" to "Amoxicillin-clavulanate",
        "d" to "Domperidone",
        "dsr" to "Domperidone",
        "od" to "Domperidone",
    )

    /**
     * A brand token, its suffix, and the hyphen that has to be between them.
     *
     * The hyphen is mandatory, and that is the whole safety of this rule. It
     * was optional at first, which meant any word of five letters or more split
     * into a stem and a one-to-three letter tail -- so "STRIP", printed on
     * every blister ever made, parsed as the brand "Stri" with the suffix "P"
     * and a fluconazole carton was reported as **paracetamol**. Real Indian
     * brands write the suffix with a hyphen (Zerodol-P, Pan-D, Zerodol-SP);
     * ordinary English words do not, and requiring it removes that entire
     * class of invention at a stroke.
     */
    private val HYPHENATED = Regex("""\b([A-Za-z]{4,})\s?-\s?([A-Za-z]{1,3})\b""")

    /**
     * The suffix only means something when it is attached to a brand, and a
     * brand is a word that is not ordinary English. Without this, "difficulty
     * in breathing" hands back "in" and "keep out of reach" hands back "of".
     */
    private val NOT_A_BRAND = setOf(
        "tablets", "tablet", "capsules", "capsule", "keep", "reach", "children",
        "store", "below", "protect", "light", "moisture", "dosage", "direction",
        "caution", "warning", "prescription", "medical", "doctor", "pharma",
        "pharmaceutical", "pharmaceuticals", "laboratories", "limited", "india",
        "manufactured", "marketed", "breathing", "itching", "swelling", "allergic",
        "difficulty", "batch", "expiry", "retail", "price", "inclusive", "taxes",
        "composition", "contains", "each", "contain", "uncoated", "coated", "film",
    )

    fun brandIn(text: String): String? = HYPHENATED.findAll(text)
        .map { it.groupValues[1] }
        .firstOrNull { it.lowercase() !in NOT_A_BRAND }
        ?: text.split(Regex("[^A-Za-z]+"))
            .firstOrNull { it.length >= 5 && it.lowercase() !in NOT_A_BRAND }

    fun infer(text: String, index: KnowledgeIndex): Lead? {
        for (match in HYPHENATED.findAll(text)) {
            val stem = match.groupValues[1]
            val suffix = match.groupValues[2].lowercase()
            if (stem.lowercase() in NOT_A_BRAND) continue
            // A stem the app already knows is not an unknown brand -- it is a
            // medicine that matched, and this code is only reached when none did.
            if (index.findMedicine(stem) != null) continue
            val generic = MEANS[suffix] ?: continue
            val doc = index.medicineByGeneric(generic) ?: continue
            return Lead(doc, "$stem-${match.groupValues[2].uppercase()}", suffix)
        }
        return null
    }
}

// --------------------------------------------------------------- 3. safety
/** Looks up what is known about this medicine during breastfeeding. */
class SafetyAgent {

    /**
     * The worst risk across every active, and a finding that names each one.
     *
     * Worst wins, and it has to: a combination is as safe as its least safe
     * ingredient, and averaging "compatible" with "avoid" across two drugs in
     * one tablet produces a number that describes neither.
     */
    fun run(docs: List<KnowledgeDoc>): Pair<String?, AgentStep> {
        if (docs.isEmpty()) {
            return null to AgentStep(
                agent = "Breastfeeding safety",
                looked_at = "Nothing to look up",
                finding = "Skipped - no medicine was identified.",
                confidence = AgentStep.Confidence.NONE,
            )
        }
        val order = listOf("compatible", "caution", "avoid")
        val risks = docs.map { it.risk ?: "caution" }
        val risk = risks.maxByOrNull { order.indexOf(it).coerceAtLeast(1) } ?: "caution"

        return risk to AgentStep(
            agent = "Breastfeeding safety",
            looked_at = docs.map { it.source }.distinct().joinToString(", "),
            finding = docs.joinToString(" ") { entry ->
                val body = entry.text.substringBefore(" Also sold as").trim()
                if (docs.size == 1) body else "${entry.generic}: $body"
            },
            confidence = AgentStep.Confidence.HIGH,
            couldNotCheck = "This is about you taking it, not about giving it to " +
                "your baby, and it never covers dose.",
            flags = docs.map { entry ->
                val label = when (entry.risk) {
                    "compatible" -> "generally compatible"
                    "avoid" -> "flagged avoid"
                    else -> "use with caution"
                }
                if (docs.size == 1) label else "${entry.generic}: $label"
            },
        )
    }
}

// ---------------------------------------------------------- 4. interactions
/**
 * The stage that makes this personal.
 *
 * Cross-checks the identified medicine against her declared conditions,
 * allergies and current medicines. This is the part a generic lookup cannot do
 * and the reason the history stage exists.
 */
class InteractionAgent {

    companion object {
        /** Words too common to carry meaning when matching a condition. */
        private val NOISE = setOf("allergy", "allergic", "disease", "disorder",
                                  "problems", "mild", "severe", "chronic", "history",
                                  "of", "and", "the", "to", "a")

        private fun terms(value: String): Set<String> = value
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in NOISE }
            .toSet()

        /**
         * True when two free-text clinical phrases refer to the same thing.
         *
         * Substring containment alone is not enough and gets the direction wrong
         * in exactly the case that matters: a user writing "penicillin" against
         * a corpus entry of "penicillin allergy". Comparing meaningful terms
         * makes the match symmetric, which is what a safety check needs.
         */
        fun overlaps(a: String, b: String): Boolean {
            val ta = terms(a)
            val tb = terms(b)
            if (ta.isEmpty() || tb.isEmpty()) return false
            return ta.any { it in tb }
        }

        fun matches(haystack: List<String>, needles: List<String>): List<String> =
            needles.filter { needle -> haystack.any { overlaps(it, needle) } }
    }

    data class Findings(
        val conditionHits: List<String>,
        val allergyHits: List<String>,
        val medicineHits: List<String>,
        val duplicate: Boolean,
    ) {
        val any: Boolean get() =
            conditionHits.isNotEmpty() || allergyHits.isNotEmpty() ||
                medicineHits.isNotEmpty()
    }

    fun run(
        docs: List<KnowledgeDoc>,
        context: HistoryAgent.MotherContext,
    ): Pair<Findings, AgentStep> {
        if (docs.isEmpty()) {
            return Findings(emptyList(), emptyList(), emptyList(), false) to AgentStep(
                agent = "Your history",
                looked_at = "Nothing to cross-check",
                finding = "Skipped - no medicine was identified.",
                confidence = AgentStep.Confidence.NONE,
            )
        }
        val doc = docs.first()

        // Every active is cross-checked, not just the headline one. A
        // combination tablet puts two drugs into the same person, and the
        // ulcer warning that belongs to one of them is not cancelled by the
        // other being harmless.
        val conditionHits = docs.flatMap { matches(context.conditions, it.avoidIf) }.distinct()

        // Allergies are matched two ways, and both matter. A stated allergy can
        // name the condition the corpus flags ("penicillin" vs "penicillin
        // allergy"), or it can name the drug itself ("amoxicillin"). Missing
        // either is the worst bug this pipeline can have.
        val allergyHits = docs.flatMap { entry ->
            matches(context.allergies, entry.avoidIf.filter { it.contains("allerg") }) +
                context.allergies.filter { allergy ->
                    overlaps(allergy, entry.generic) ||
                        entry.aliases.any { overlaps(allergy, it) } ||
                        entry.avoidIf.any { overlaps(allergy, it) }
                }
        }.distinct()
        val medicineHits = context.currentMedicines.filter { taking ->
            docs.any { entry -> entry.interactsWith.any { overlaps(taking, it) } }
        }
        val duplicate = context.currentMedicines.any { taking ->
            docs.any { entry ->
                overlaps(taking, entry.generic) || entry.aliases.any { overlaps(taking, it) }
            }
        }

        val findings = Findings(conditionHits, allergyHits.distinct(),
                                medicineHits, duplicate)

        val step = AgentStep(
            agent = "Your history",
            looked_at = "${docs.joinToString(" + ") { it.generic }} against " +
                "${context.conditions.size} conditions, " +
                "${context.allergies.size} allergies, " +
                "${context.currentMedicines.size} current medicines",
            finding = when {
                allergyHits.isNotEmpty() ->
                    "STOP - this appears to match an allergy you listed " +
                        "(${allergyHits.joinToString(", ")})."
                conditionHits.isNotEmpty() ->
                    "Flagged: usually avoided with " +
                        "${conditionHits.joinToString(", ")}. ${doc.contextNote}".trim()
                duplicate ->
                    "You may already be taking this - check before adding another."
                medicineHits.isNotEmpty() ->
                    "May interact with ${medicineHits.joinToString(", ")}. " +
                        doc.contextNote
                context.conditions.isEmpty() && context.currentMedicines.isEmpty() ->
                    "Nothing on file to cross-check against."
                else -> "No conflict found with what you have on file."
            },
            confidence = if (context.conditions.isEmpty() &&
                context.currentMedicines.isEmpty() && context.allergies.isEmpty())
                AgentStep.Confidence.NONE else AgentStep.Confidence.HIGH,
            couldNotCheck = "I only know what is in your profile and records. " +
                "Anything you have not written down, I cannot weigh.",
            flags = buildList {
                allergyHits.forEach { add("allergy: $it") }
                conditionHits.forEach { add("condition: $it") }
                medicineHits.forEach { add("interaction: $it") }
                if (duplicate) add("possible duplicate")
            },
        )
        return findings to step
    }
}

// ------------------------------------------------- 4b. DrugBank interactions
/**
 * The same question as [InteractionAgent], asked of a much bigger table.
 *
 * The curated `interacts_with` lists are hand-written, sourced and cover
 * thirty-four medicines. Everything a mother is *actually* taking -- her
 * thyroid tablet, her metformin, the antidepressant nobody wrote a corpus entry
 * for -- falls outside them, and outside them the app had nothing to say.
 *
 * This stage runs afterwards over DrugBank's own interaction graph: 1,710 drugs
 * and 188,240 recorded interactions, with the type named by a classifier
 * trained on the DDI-Bench random split. It is an addition and never a
 * subtraction. If a table is missing, if a name does not resolve, if the model
 * will not load or inference throws, the stage contributes nothing and the
 * verdict is exactly the one the four curated stages reached.
 *
 * It can raise the verdict to CAUTION -- a recorded interaction with something
 * she is already taking is worth a question at the counter -- and it can never
 * lower one.
 */
class DrugInteractionAgent(
    private val index: KnowledgeIndex,
    private val interactions: DrugInteractionLookup?,
) {

    /**
     * The one thing this agent needs from the ddi package.
     *
     * An interface rather than the class, so the review pipeline stays testable
     * off-device: [com.nila.ddi.DrugInteractions] needs an Android `Context`,
     * an ONNX runtime and 20 MB of weights, and none of that belongs in a unit
     * test about what the verdict says.
     */
    interface DrugInteractionLookup {
        fun resolve(name: String): Int?
        fun between(drug1: Int, drug2: Int): Finding?
    }

    /** One recorded interaction. [description] is null when unnamed. */
    data class Finding(
        val subject: String,
        val other: String,
        val description: String?,
        val reliable: Boolean,
    )

    data class Findings(val hits: List<Finding>) {
        val any: Boolean get() = hits.isNotEmpty()
    }

    /**
     * A line from her medicines list, as DrugBank node ids.
     *
     * The corpus is asked first and DrugBank second, because people write down
     * what is on the box. "Crocin 650" and "Combiflam" are not DrugBank names
     * and never will be, but they are in `knowledge.json` with the node ids of
     * what is in them -- and going through it also means one line can resolve
     * to two drugs, which is what a combination she is already taking actually
     * is. DrugBank's own vocabulary then catches everything the corpus has no
     * entry for, which is the whole point of this stage: warfarin, metformin,
     * levothyroxine, sertraline.
     */
    private fun resolve(name: String, lookup: DrugInteractionLookup): List<Int> {
        index.findMedicine(name)?.doc?.ddi?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOfNotNull(lookup.resolve(name))
    }

    fun run(
        docs: List<KnowledgeDoc>,
        context: HistoryAgent.MotherContext,
    ): Pair<Findings, AgentStep>? {
        val lookup = interactions ?: return null
        val scanned = docs.flatMap { it.ddi }.distinct()
        if (scanned.isEmpty() || context.currentMedicines.isEmpty()) return null

        val taking = context.currentMedicines
            .flatMap { name -> resolve(name, lookup).map { name to it } }
            .distinctBy { it.second }
        if (taking.isEmpty()) return null

        val hits = buildList {
            for (drug in scanned) {
                for ((_, other) in taking) {
                    if (drug == other) continue
                    lookup.between(drug, other)?.let(::add)
                }
            }
        }.distinctBy { it.subject to it.other }

        val named = hits.filter { it.description != null }
        val step = AgentStep(
            agent = "Drug interactions",
            looked_at = "${docs.joinToString(" + ") { it.generic }} against " +
                "${taking.size} of the medicines you listed, in DrugBank",
            finding = when {
                hits.isEmpty() ->
                    "DrugBank records no interaction between these and what you " +
                        "are taking."
                named.isNotEmpty() ->
                    named.joinToString(" ") { it.description!! }
                else ->
                    "DrugBank records an interaction with " +
                        hits.joinToString(", ") { it.other } +
                        ", but I cannot say what kind."
            },
            confidence = when {
                hits.isEmpty() -> AgentStep.Confidence.MEDIUM
                named.isNotEmpty() -> AgentStep.Confidence.MEDIUM
                else -> AgentStep.Confidence.LOW
            },
            // The honest limit of this table, stated every time it is used. It
            // says nothing about dose, nothing about how much any of it
            // matters for one person, and an absence in it is not a clearance.
            couldNotCheck = "DrugBank lists which pairs interact, not how much it " +
                "matters for you or at what dose. Nothing recorded is not the " +
                "same as nothing to worry about.",
            flags = hits.map { "DrugBank: ${it.subject} with ${it.other}" },
        )
        return Findings(hits) to step
    }
}

// --------------------------------------------------------------- 5. verdict
/**
 * Combines the findings into one answer.
 *
 * The severity ladder is explicit and the worst finding wins -- an allergy match
 * outranks a reassuring safety entry, always. That ordering is the whole reason
 * this is a pipeline and not a single similarity search.
 */
class AdjudicatorAgent {

    fun run(
        identified: IdentifyAgent.Identified,
        risk: String?,
        interactions: InteractionAgent.Findings,
        drugBank: DrugInteractionAgent.Findings?,
        context: HistoryAgent.MotherContext,
        language: String,
        steps: List<AgentStep>,
    ): MedicineReviewResult {
        val doc = identified.doc
        val docs = identified.docs
        val ruled = when {
            doc == null -> Verdict.UNKNOWN
            interactions.allergyHits.isNotEmpty() -> Verdict.AVOID
            risk == "avoid" -> Verdict.AVOID
            interactions.conditionHits.isNotEmpty() -> Verdict.CAUTION
            interactions.medicineHits.isNotEmpty() || interactions.duplicate ->
                Verdict.CAUTION
            // The addon may raise the answer to a question and never lower it.
            // A pair DrugBank records is a real finding about two drugs going
            // into one person, and "ask before you add this" is the right
            // response to it -- but it never turns a CAUTION into a clearance,
            // because the rule above it has already fired by then.
            drugBank?.any == true -> Verdict.CAUTION
            risk == "caution" -> Verdict.CAUTION
            risk == "compatible" && !context.breastfeeding -> Verdict.LIKELY_SAFE
            risk == "compatible" -> Verdict.LIKELY_SAFE
            else -> Verdict.UNKNOWN
        }

        // A composition read off a brand name rather than off the packet may
        // raise concern and may never lower it. Telling somebody a tablet is
        // "likely safe" on the strength of what its *name* suggests is in it
        // is the one output this path must not be able to produce -- so a
        // guess that lands on LIKELY_SAFE is demoted to a question for the
        // pharmacist, while a guess that lands on AVOID is kept in full.
        val verdict = if (identified.inferred && ruled == Verdict.LIKELY_SAFE)
            Verdict.CAUTION else ruled

        val names = docs.joinToString(" and ") { it.generic }
        val headline = when {
            verdict == Verdict.AVOID && interactions.allergyHits.isNotEmpty() ->
                "Do not take this - it matches an allergy you listed"
            verdict == Verdict.AVOID -> "Best avoided while breastfeeding"
            verdict == Verdict.CAUTION && identified.inferred ->
                "Check what is in this before you take it"
            verdict == Verdict.CAUTION -> "Ask your doctor before taking this"
            verdict == Verdict.LIKELY_SAFE && docs.size > 1 ->
                "Both ingredients are generally considered compatible"
            verdict == Verdict.LIKELY_SAFE ->
                "Generally considered compatible with breastfeeding"
            else -> "I could not check this one"
        }

        // Order and suppression both matter here. A parent who has just read
        // "do not take this" must not then read "generally compatible with
        // breastfeeding" in the same paragraph -- the general entry is true but
        // irrelevant once a personal contraindication has fired, and printing it
        // anyway reads as the app contradicting itself.
        val personal = listOfNotNull(
            steps.firstOrNull { it.agent == "Your history" && it.flags.isNotEmpty() }
                ?.finding,
            // Only when it actually found something. The addon's "nothing
            // recorded" line is true and reassuring and therefore exactly what
            // must not appear in a paragraph under an AVOID headline.
            steps.firstOrNull { it.agent == "Drug interactions" && it.flags.isNotEmpty() }
                ?.finding,
        ).joinToString(" ").takeIf { it.isNotBlank() }
        // The detail without the risk label: the headline has already said
        // whether it is safe, and repeating that verbatim reads as a stutter.
        val generalEntry = docs
            .map { it.detail.takeIf(String::isNotBlank)
                ?: it.text.substringBefore(" Also sold as").trim() }
            .filter { it.isNotBlank() }
            .let { bodies ->
                when {
                    bodies.isEmpty() -> null
                    bodies.size == 1 -> bodies.first()
                    else -> docs.indices.joinToString(" ") {
                        "${docs[it].generic}: ${bodies[it]}" }
                }
            }
        val contradicts = verdict == Verdict.AVOID || verdict == Verdict.CAUTION

        val fallback = buildString {
            append(headline)
            append(". ")
            // The hedge goes first, before any sourced sentence about the drug.
            // Placed after, it read as a footnote to a fact; placed here it is
            // the frame the reader takes the rest of the paragraph in.
            if (identified.inferred) {
                append("I worked out that ${identified.brand ?: "this"} contains " +
                    "${names.lowercase()} from its name, not from the packet, so " +
                    "check the composition line before you rely on any of this. ")
            }
            personal?.let { append(it).append(" ") }
            if (generalEntry != null && !(contradicts && personal != null)) {
                append(generalEntry).append(" ")
            } else if (generalEntry != null) {
                append("The general guidance for ${names.ifBlank { "this medicine" }} " +
                    "is different, but your own history takes priority. ")
            }
            append("Confirm with your doctor or pharmacist.")
        }.trim()

        return MedicineReviewResult(
            verdict = verdict,
            headline = headline,
            steps = steps,
            medicine = doc,
            sources = docs.map { "${it.title} - ${it.source}" },
            cautions = docs.mapNotNull { it.caution.takeIf(String::isNotBlank) }.distinct(),
            summary = fallback,
            summaryFromLlm = false,
            displayName = when {
                identified.inferred -> identified.brand ?: names
                docs.isNotEmpty() -> docs.joinToString(" + ") { it.generic }
                else -> identified.brand.orEmpty()
            },
        )
    }

    companion object {
        /**
         * The prompt used to rewrite an already-decided verdict.
         *
         * Framed as a completion rather than a list of orders: a 0.5B model
         * handed imperative instructions tends to repeat them back as if they
         * were the answer.
         */
        fun refinementPrompt(result: MedicineReviewResult, language: String): String =
            buildString {
                appendLine("Notes from a medicine safety check for a " +
                    "breastfeeding mother:")
                result.steps.forEach { appendLine("${it.agent}: ${it.finding}") }
                appendLine("Overall: ${result.headline}")
                appendLine()
                appendLine(
                    "A midwife explains this to her in two or three plain " +
                        "sentences${if (language == "en") "" else " in $language"}, " +
                        "without mentioning any dose:"
                )
                appendLine()
            }
    }
}

/** Runs the five stages in order and returns the full trace. */
/**
 * Runs the stages in order.
 *
 * The verdict is produced by rules alone and returns immediately. The language
 * model is not in this path at all, and there is no longer one in the app to
 * put here. A small model was tried and produced invented causal claims and a
 * softened contradiction of its own AVOID verdict; the verdict text is
 * assembled from retrieved, sourced facts and needs no help.
 */
class MedicineReviewPipeline(
    private val index: KnowledgeIndex,
    private val records: HealthRecordDao,
    /**
     * The DrugBank addon, or null to run exactly the pipeline that shipped
     * before it existed. Null is not a degraded mode; it is the old behaviour,
     * unchanged, and every test written against it still describes it.
     */
    drugBank: DrugInteractionAgent.DrugInteractionLookup? = null,
) {
    private val history = HistoryAgent(records)
    private val identify = IdentifyAgent(index)
    private val safety = SafetyAgent()
    private val interactions = InteractionAgent()
    private val drugInteractions = DrugInteractionAgent(index, drugBank)
    private val adjudicator = AdjudicatorAgent()

    suspend fun review(
        ocrText: String,
        profile: MotherProfile?,
        language: String = "en",
    ): MedicineReviewResult {
        val steps = mutableListOf<AgentStep>()

        val (context, historyStep) = history.run(profile)
        steps += historyStep

        val (identified, identifyStep) = identify.run(ocrText)
        steps += identifyStep

        val (risk, safetyStep) = safety.run(identified.docs)
        steps += safetyStep

        val (interactionFindings, interactionStep) =
            interactions.run(identified.docs, context)
        steps += interactionStep

        // The addon, last and optional. Anything it throws is swallowed here
        // rather than inside it, so a bug added to it later still cannot reach
        // a screen somebody is reading a safety verdict on.
        //
        // Not run at all on an ingredient inferred from a brand name. Naming a
        // specific DrugBank interaction between her warfarin and a paracetamol
        // we only *guessed* is in the tablet states a fact about a packet
        // nobody has read, and the verdict is already CAUTION on that path, so
        // it would add alarm without adding an answer.
        val drugBankFindings =
            if (identified.inferred) null
            else runCatching { drugInteractions.run(identified.docs, context) }.getOrNull()
        drugBankFindings?.let { steps += it.second }

        return adjudicator.run(identified, risk, interactionFindings,
                               drugBankFindings?.first, context, language, steps)
    }

}
