package com.nila

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.ddi.DrugInteractions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DrugBank interaction model, on the phone.
 *
 * The point of running this on a device rather than on the JVM is that nothing
 * else proves the exported graph survives ONNX Runtime's ARM build. The model
 * uses multi-head attention and layer normalisation, and "the operator set is
 * supported on this platform" is not a thing a desktop run can establish.
 *
 * The expected classes and probabilities were produced by the PyTorch
 * checkpoint on a workstation. Agreement here means the whole chain --
 * export, asset packing, the feature transform reimplemented in Kotlin, ORT on
 * ARM -- reproduces the model that was measured.
 */
@RunWith(AndroidJUnit4::class)
class DrugInteractionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var ddi: DrugInteractions

    /**
     * Pairs where both drugs were in the model's training set.
     *
     * The probability is what the PyTorch checkpoint produced on a
     * workstation. Every one of these also happens to match the label DrugBank
     * actually records for the pair, which is the point of quoting real
     * medicines rather than arbitrary node ids.
     *
     * The direction is DrugBank's, not the order written here: the graph
     * records each interaction once, and [DrugInteractions.between] asks the
     * model the way round it was recorded so the sentence template lands on
     * the right subject.
     */
    private val bothTrained = listOf(
        Triple("Acetaminophen", "Warfarin", 0.9972f),
        Triple("Ibuprofen", "Prednisolone", 0.9929f),
        Triple("Clarithromycin", "Amlodipine", 0.9968f),
        Triple("Fluconazole", "Warfarin", 0.8128f),
        Triple("Sertraline", "Codeine", 0.6380f),
    )

    /**
     * Pairs DrugBank records where one drug is outside the training set.
     *
     * Top-1 on these is 69.7% against 96.6% when both drugs were seen, so the
     * type must be withheld and only the recorded interaction reported. Both
     * of these are in fact ones the model gets wrong -- it calls
     * Sertraline/Tramadol serotonergic where DrugBank says neuroexcitatory,
     * and misses the QTc prolongation entirely on Ondansetron/Azithromycin --
     * which is exactly why this path exists.
     */
    private val oneNovel = listOf(
        "Tramadol" to "Sertraline",
        "Azithromycin" to "Ondansetron",
    )

    @Before
    fun setUp() {
        ddi = DrugInteractions.shared(context)
        ddi.prepare()
    }

    @Test
    fun theVocabularyLoaded() {
        assertEquals(1710, ddi.vocabularySize)
        assertEquals(228, ddi.resolve("Acetaminophen"))
        assertEquals(334, ddi.resolve("Ibuprofen"))
        // The way somebody actually writes a medicines list.
        assertEquals(334, ddi.resolve("Ibuprofen 400mg"))
        assertEquals(219, ddi.resolve("Tab. Warfarin"))
        assertNull(ddi.resolve("some herbal tonic"))
        assertNull(ddi.resolve(""))
    }

    @Test
    fun predictionsMatchThePyTorchCheckpoint() {
        for ((first, second, probability) in bothTrained) {
            val a = ddi.resolve(first)!!
            val b = ddi.resolve(second)!!
            val result = ddi.between(a, b)
            assertNotNull("$first + $second: DrugBank records no edge", result)
            assertNotNull("$first + $second: type was withheld", result!!.description)
            Log.i("DdiDeviceTest",
                  "$first + $second -> ${result.description} (p=${result.confidence})")
            assertEquals(
                "$first + $second: probability drifted from the checkpoint",
                probability, result.confidence, 0.01f,
            )
        }
    }

    @Test
    fun aDrugTheModelNeverSawGetsNoNamedType() {
        for ((first, second) in oneNovel) {
            val a = ddi.resolve(first)!!
            val b = ddi.resolve(second)!!
            val result = ddi.between(a, b)
            assertNotNull("$first + $second: DrugBank records no edge", result)
            assertTrue("$first + $second was presented as reliable", !result!!.reliable)
            assertNull(
                "$first + $second: a type was named for a drug the model never " +
                    "saw -- ${result.description}",
                result.description,
            )
            // The recorded interaction is still reported; only the type is not.
            assertTrue(result.recorded)
            Log.i("DdiDeviceTest", "$first + $second -> recorded, type withheld")
        }
    }

    @Test
    fun aPairDrugBankDoesNotRecordSaysNothing() {
        // The classifier has no "they do not interact" class and would return
        // one of 86 types for any pair put in front of it. The graph is what
        // stops it being asked.
        var silent = 0
        var asked = 0
        val ids = listOf(228, 334, 446, 645, 554, 82, 219, 998, 408, 411)
        for (a in ids) for (b in ids) {
            if (a == b) continue
            asked++
            if (ddi.between(a, b) == null) silent++
        }
        Log.i("DdiDeviceTest", "$silent of $asked pairs produced no answer")
        assertTrue("every pair produced an interaction, so the graph is not " +
            "being consulted", silent > 0)
    }

    @Test
    fun oneLookupIsFastEnoughToSitInAScan() {
        val a = ddi.resolve("Acetaminophen")!!
        val b = ddi.resolve("Warfarin")!!
        repeat(3) { ddi.between(a, b) }          // warm
        val started = System.nanoTime()
        repeat(20) { ddi.between(a, b) }
        val each = (System.nanoTime() - started) / 20 / 1_000_000.0
        Log.i("DdiDeviceTest", "one interaction lookup: ${"%.1f".format(each)} ms")
        assertTrue("a lookup took ${each}ms, which would be felt in the scan",
                   each < 250.0)
    }
}
