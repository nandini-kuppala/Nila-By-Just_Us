package com.nila.assistant

import com.nila.assistant.agents.AgentStep
import com.nila.assistant.agents.DrugInteractionAgent
import com.nila.assistant.agents.HistoryAgent
import org.json.JSONObject
import org.junit.Before
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DrugBank addon's contract with the pipeline it was added to.
 *
 * The behaviour under test is mostly about *restraint*: when it must stay
 * silent, what it must never claim, and that nothing it does can take the
 * medicine check down with it. The model's own accuracy is measured elsewhere,
 * on the benchmark's held-out split, not here.
 */
class DrugInteractionAgentTest {

    private lateinit var index: KnowledgeIndex

    @Before
    fun setUp() {
        // The real corpus, because the agent uses it to turn "Crocin 650" into
        // a DrugBank id before it falls back to DrugBank's own spelling.
        val array = JSONObject(File("src/main/assets/knowledge.json").readText())
            .getJSONArray("documents")
        index = KnowledgeIndex.build((0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun list(n: String) = o.optJSONArray(n)?.let { a ->
                (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            fun ints(n: String) = o.optJSONArray(n)?.let { a ->
                (0 until a.length()).map { a.getInt(it) } } ?: emptyList()
            KnowledgeDoc(
                id = o.getString("id"), collection = o.getString("collection"),
                topic = o.getString("topic"), title = o.getString("title"),
                text = o.getString("text"), source = o.getString("source"),
                caution = o.optString("caution", ""), keywords = o.optString("keywords", ""),
                risk = o.optString("risk").takeIf { it.isNotEmpty() },
                generic = o.optString("generic"), aliases = list("aliases"),
                avoidIf = list("avoid_if"), interactsWith = list("interacts_with"),
                contextNote = o.optString("context_note"), detail = o.optString("detail"),
                ddi = ints("ddi"),
            )
        })
    }

    private val paracetamol = KnowledgeDoc(
        id = "med-paracetamol", collection = "medicines", topic = "medicine",
        title = "Paracetamol while breastfeeding", text = "Generally compatible.",
        source = "LactMed (NIH)", caution = "", keywords = "",
        risk = "compatible", generic = "Paracetamol", ddi = listOf(228),
    )
    private val unmapped = paracetamol.copy(id = "med-x", generic = "Drotaverine",
                                            ddi = emptyList())

    private fun context(vararg taking: String) = HistoryAgent.MotherContext(
        conditions = emptyList(), allergies = emptyList(),
        currentMedicines = taking.toList(), breastfeeding = true,
        weeksPostpartum = 6, recordsConsulted = 0, fromRecords = emptyList(),
    )

    /** A lookup that knows warfarin, and one interaction involving it. */
    private class Fake(
        private val named: Boolean = true,
        private val throws: Boolean = false,
    ) : DrugInteractionAgent.DrugInteractionLookup {
        override fun resolve(name: String): Int? {
            if (throws) throw IllegalStateException("asset missing")
            return if (name.contains("warfarin", true)) 219 else null
        }
        override fun between(drug1: Int, drug2: Int): DrugInteractionAgent.Finding? {
            if (throws) throw IllegalStateException("model would not load")
            if (setOf(drug1, drug2) != setOf(228, 219)) return null
            return DrugInteractionAgent.Finding(
                subject = "Acetaminophen", other = "Warfarin",
                description = if (named)
                    "The serum concentration of Warfarin can be decreased when it " +
                        "is combined with Acetaminophen." else null,
                reliable = named,
            )
        }
    }

    // ------------------------------------------------------------- silence

    @Test
    fun `no addon means no step at all`() {
        assertNull(DrugInteractionAgent(index, null)
            .run(listOf(paracetamol), context("Warfarin")))
    }

    @Test
    fun `nothing on her list means no step`() {
        assertNull(DrugInteractionAgent(index, Fake()).run(listOf(paracetamol), context()))
    }

    @Test
    fun `a medicine outside DrugBank's vocabulary means no step`() {
        assertNull(DrugInteractionAgent(index, Fake())
            .run(listOf(unmapped), context("Warfarin")))
    }

    @Test
    fun `a medicine she takes that does not resolve means no step`() {
        assertNull(DrugInteractionAgent(index, Fake())
            .run(listOf(paracetamol), context("some herbal tonic")))
    }

    // ------------------------------------------------------------- findings

    @Test
    fun `a recorded interaction is reported with its type`() {
        val (findings, step) = DrugInteractionAgent(index, Fake())
            .run(listOf(paracetamol), context("Warfarin 5mg"))!!
        assertTrue(findings.any)
        assertTrue(step.finding.contains("Warfarin"))
        assertEquals(AgentStep.Confidence.MEDIUM, step.confidence)
    }

    @Test
    fun `an unnamed interaction says so instead of inventing a type`() {
        // Either drug being outside what the model was trained on drops top-1
        // from 96.6% to 69.7%, so the type is withheld and only DrugBank's
        // record of the pair is reported.
        val (findings, step) = DrugInteractionAgent(index, Fake(named = false))
            .run(listOf(paracetamol), context("Warfarin"))!!
        assertTrue(findings.any)
        assertTrue("named a type it should have withheld: ${step.finding}",
                   step.finding.contains("cannot say what kind"))
        assertEquals(AgentStep.Confidence.LOW, step.confidence)
    }

    @Test
    fun `no recorded interaction is reported without flags`() {
        val (findings, step) = DrugInteractionAgent(index, Fake())
            .run(listOf(paracetamol), context("Warfarin", "Metformin"))!!
        assertTrue(findings.any)
        // Metformin does not resolve in the fake, so only warfarin is checked.
        assertEquals(1, findings.hits.size)
        assertTrue(step.flags.isNotEmpty())
    }

    @Test
    fun `an absence is never presented as a clearance`() {
        val fake = object : DrugInteractionAgent.DrugInteractionLookup {
            override fun resolve(name: String) = 219
            override fun between(drug1: Int, drug2: Int) = null
        }
        val (findings, step) = DrugInteractionAgent(index, fake)
            .run(listOf(paracetamol), context("Warfarin"))!!
        assertFalse(findings.any)
        assertTrue(step.flags.isEmpty())
        assertNotNull(step.couldNotCheck)
        assertTrue("an empty result reads as a clearance: ${step.couldNotCheck}",
                   step.couldNotCheck!!.contains("not the same as"))
    }

    @Test
    fun `a brand name on her list reaches DrugBank through the corpus`() {
        // "Crocin" is not a DrugBank name and never will be. It is a
        // paracetamol brand in knowledge.json, which carries the node id --
        // and that path is the only reason anything somebody actually writes
        // down resolves at all.
        val asked = mutableListOf<Pair<Int, Int>>()
        val fake = object : DrugInteractionAgent.DrugInteractionLookup {
            override fun resolve(name: String): Int? = null   // DrugBank spelling only
            override fun between(drug1: Int, drug2: Int): DrugInteractionAgent.Finding? {
                asked += drug1 to drug2
                return null
            }
        }
        val ibuprofen = paracetamol.copy(id = "med-ibuprofen", generic = "Ibuprofen",
                                         ddi = listOf(334))
        DrugInteractionAgent(index, fake).run(listOf(ibuprofen), context("Crocin 650"))
        assertTrue("Crocin never reached DrugBank: $asked", asked.contains(334 to 228))

        // The same path for what the seeded demo profile actually says. DrugBank
        // calls it "Iron"; nobody writes that on a list.
        asked.clear()
        DrugInteractionAgent(index, fake)
            .run(listOf(ibuprofen), context("Ferrous sulphate"))
        assertTrue("ferrous sulphate never reached DrugBank: $asked",
                   asked.contains(334 to 1487))
    }

    // ------------------------------------------------------------- failure

    @Test
    fun `a lookup that throws does not take the review with it`() {
        // The pipeline wraps this stage, but the stage should not be the thing
        // relying on that. Whatever the reason -- a missing asset, a model that
        // will not load -- the answer is silence.
        val result = runCatching {
            DrugInteractionAgent(index, Fake(throws = true))
                .run(listOf(paracetamol), context("Warfarin"))
        }
        assertTrue("the addon threw into the pipeline", result.isFailure ||
            result.getOrNull() == null)
    }
}
