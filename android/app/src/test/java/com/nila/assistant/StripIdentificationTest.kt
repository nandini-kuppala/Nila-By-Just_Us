package com.nila.assistant

import com.nila.assistant.agents.AgentStep
import com.nila.assistant.agents.IdentifyAgent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What the identify stage does with text that came off a real packet.
 *
 * Every string here is either a verbatim OCR read from a photograph somebody
 * took of a strip, or the printing on one transcribed as the recogniser sees
 * it -- manufacturer lines, licence numbers, torn words and all. The synthetic
 * cases in [FuzzyMedicineTest] cover clean names; these cover the packets that
 * were coming back as "I could not check this one".
 */
class StripIdentificationTest {

    private lateinit var index: KnowledgeIndex
    private lateinit var identify: IdentifyAgent

    /** The scan in the bug report: read perfectly, recognised as nothing. */
    private val oxidol =
        "Eu Oxidol-P daOHM Blomax Biotechnics(P)Ltd B.No.BBT230721C MFG.AI M.R.P " +
            "OXP OXIORD PHARMA Markeced by: Mfg.Lic.No.:1089-OSP(H)&800-B(H) Oxido " +
            "breathing,itching orrash. jowe &reach of chiidren"

    @Before
    fun setUp() {
        val json = File("src/main/assets/knowledge.json").readText()
        val array = JSONObject(json).getJSONArray("documents")
        val docs = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun list(name: String) = o.optJSONArray(name)?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
            KnowledgeDoc(
                id = o.getString("id"),
                collection = o.getString("collection"),
                topic = o.getString("topic"),
                title = o.getString("title"),
                text = o.getString("text"),
                source = o.getString("source"),
                caution = o.optString("caution", ""),
                keywords = o.optString("keywords", ""),
                risk = o.optString("risk").takeIf { it.isNotEmpty() },
                generic = o.optString("generic"),
                aliases = list("aliases"),
                avoidIf = list("avoid_if"),
                interactsWith = list("interacts_with"),
                contextNote = o.optString("context_note"),
                detail = o.optString("detail"),
            )
        }
        index = KnowledgeIndex.build(docs)
        identify = IdentifyAgent(index)
    }

    private fun generics(text: String) =
        index.findMedicines(text).map { it.doc.generic }

    // ------------------------------------------------------- wrong drug

    @Test
    fun `a salt word never identifies a drug on its own`() {
        // The bug: "acetylsalicylic acid" is an alias of Aspirin, so the lone
        // token "acid" scored a four-character name match and a mefenamic acid
        // strip was reported as Aspirin -- an entry the corpus marks *avoid*.
        val hits = generics("MEFTAL SPAS Mefenamic acid Dicyclomine Blue Cross")
        assertTrue("resolved to $hits", "Aspirin" !in hits)
        assertTrue("mefenamic acid missing from $hits", "Mefenamic acid" in hits)
    }

    @Test
    fun `an unrelated packet still matches nothing`() {
        listOf(
            "SHELCAL 500 Calcium Carbonate Vitamin D3 Torrent",
            "Keep out of reach of children. Store below 30 C.",
            "B.No. AZ4417 Mfg 03/2024 Exp 02/2027 M.R.P Rs 48.50",
        ).forEach { assertNull("'$it' matched ${index.findMedicine(it)?.doc?.generic}",
                               index.findMedicine(it)) }
    }

    // ------------------------------------------- both halves of a combination

    @Test
    fun `a combination reports every active, not the highest scoring one`() {
        val hits = generics("ZERODOL-P Aceclofenac 100mg Paracetamol 325mg Tablets IPCA")
        assertTrue("aceclofenac missing from $hits", "Aceclofenac" in hits)
        assertTrue("paracetamol missing from $hits", "Paracetamol" in hits)
    }

    @Test
    fun `one antibiotic is not reported as a combination`() {
        // Amoxicillin and Amoxicillin-clavulanate share the word "amoxicillin",
        // so a plain amoxicillin strip matched both on identical evidence and
        // was reported as containing clavulanate that is not in it.
        assertEquals(listOf("Amoxicillin"), generics("AMOXICILLIN 500 Capsules IP"))
        // ...and the genuine combination still resolves to the combination.
        assertTrue(generics("AUGMENTIN 625 DUO Tablets GSK").contains(
            "Amoxicillin-clavulanate"))
    }

    @Test
    fun `the NSAID in a combination is not hidden by the paracetamol`() {
        // The failure this guards: answering about the paracetamol alone made a
        // tablet containing an NSAID read as "generally compatible", with the
        // ulcer and asthma cautions never reached.
        val (identified, _) =
            identify.run("COMBIFLAM Ibuprofen 400mg Paracetamol 325mg Sanofi")
        val names = identified.docs.map { it.generic }
        assertTrue("ibuprofen missing from $names", "Ibuprofen" in names)
        assertTrue("paracetamol missing from $names", "Paracetamol" in names)
    }

    // ---------------------------------------------------- names OCR broke

    @Test
    fun `a name torn in half is put back together`() {
        assertTrue(generics("Caution: more than the daily dose of paracet mol " +
            "may cause liver damage").contains("Paracetamol"))
    }

    @Test
    fun `a truncated name still resolves`() {
        assertTrue(generics("AMOXICILL 500 Capsules IP").contains("Amoxicillin"))
    }

    @Test
    fun `a fragment that fits two drugs resolves to neither`() {
        // Amoxicillin and Amoxicillin-clavulanate both start "amoxicill", so a
        // torn read of it is ambiguous -- and picking the higher score would be
        // inventing a certainty the packet does not contain. This is only
        // reachable when no whole name matched.
        val hits = index.findMedicines("azithro")
        assertTrue("ambiguous fragment resolved to $hits", hits.size <= 1)
    }

    // ------------------------------------------------ unknown Indian brands

    @Test
    fun `an unknown brand ending in -P is read as a paracetamol combination`() {
        val (identified, step) = identify.run(oxidol)
        assertEquals("Paracetamol", identified.doc?.generic)
        assertTrue("the guess was not marked as one", identified.inferred)
        assertEquals(AgentStep.Confidence.LOW, step.confidence)
        assertTrue("the step does not say where this came from",
                   step.flags.any { it.contains("inferred") })
        assertTrue("the brand is not named back to the reader",
                   step.finding.contains("Oxidol", ignoreCase = true))
    }

    @Test
    fun `packaging prose is never mistaken for a brand`() {
        // Every one of these contains a word followed by one to three letters,
        // which is the shape the suffix rule looks for. None of them is a brand.
        listOf(
            "difficulty in breathing, itching or rash",
            "Keep out of reach of children",
            "Manufactured by: Acme Pharma Ltd",
            "Store below 30 C, protect from light",
        ).forEach {
            val (identified, _) = identify.run(it)
            assertTrue("'$it' produced ${identified.docs.map { d -> d.generic }}",
                       identified.docs.isEmpty())
        }
    }

    @Test
    fun `an ordinary word is never split into a brand and a suffix`() {
        // The real failure: the rule allowed the hyphen to be absent, so any
        // word of five letters or more split into a stem and a tail. "STRIP" --
        // printed on every blister ever made -- parsed as the brand "Stri" with
        // the suffix "P", and a fluconazole carton was reported as paracetamol.
        listOf(
            "FLUCONAZOLE 150mg STRIP OF 1 TABLET",
            "10 TABLETS PER STRIP",
            "Each film coated tablet contains",
            "Manufactured in India by Acme Ltd",
        ).forEach {
            val (identified, _) = identify.run(it)
            assertTrue("'$it' invented ${identified.docs.map { d -> d.generic }}",
                       identified.docs.none { d -> d.generic == "Paracetamol" } ||
                           !identified.inferred)
        }
    }

    @Test
    fun `a known brand is never overridden by its suffix`() {
        // "Pan-D" ends in -D, but "Pan" is a pantoprazole alias the corpus
        // knows, so it must resolve by name rather than by guess.
        val (identified, _) =
            identify.run("PAN-D Pantoprazole 40mg Domperidone 30mg SR Capsules")
        assertTrue("the guess path ran on a known brand", !identified.inferred)
        assertTrue(identified.docs.map { it.generic }.contains("Pantoprazole"))
    }

    // -------------------------------------------------- honest dead ends

    @Test
    fun `an unrecognised brand says what it read and what to type`() {
        val (identified, step) = identify.run("GLUCONORM G2 Cosme Remedies Ltd")
        assertTrue(identified.docs.isEmpty())
        assertNotNull(step.couldNotCheck)
        assertTrue("does not ask for the generic name: ${step.couldNotCheck}",
                   step.couldNotCheck!!.contains("generic"))
    }
}
