package com.nila.babyproof

import com.nila.babyproof.Tiles
import com.nila.babyproof.Box
import com.nila.babyproof.Candidate
import com.nila.babyproof.CropAnswer
import com.nila.babyproof.Hazard
import com.nila.babyproof.Prompts
import com.nila.babyproof.Rules
import com.nila.babyproof.SceneItem
import com.nila.babyproof.Source
import com.nila.babyproof.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RulesTest {

    private fun hazard(id: String, category: String, critical: Boolean, threshold: Float = 0.1f) =
        Hazard(id, id, category, critical, threshold, id, "fix $id", Source("s", "u"), IntArray(16), IntArray(16))

    private val socket = hazard("socket", "electric", true)
    private val shelf = hazard("bookshelf", "tip-over", false)
    private val coins = hazard("coins", "choking", true)
    private val box = Box(0.1f, 0.1f, 0.2f, 0.2f)

    private fun answer(h: String, reach: Boolean? = true, prot: Boolean? = false, obj: String = "thing") =
        CropAnswer(obj, h, reach, prot, "raw")

    @Test fun `the shipped catalogue parses and every entry has a fix and a source`() {
        val f = listOf("src/main/assets/babyproof/hazards.json", "app/src/main/assets/babyproof/hazards.json")
            .map(::File).first { it.exists() }
        val all = Hazard.parseAll(f.readText())
        assertTrue(all.size >= 30)
        all.forEach {
            assertTrue(it.id, it.fix.length > 20 && it.source.url.startsWith("https://"))
            assertEquals(16, it.inputIds.size)
            assertEquals(49406, it.inputIds[0])
            // The detector scores each patch against this: it must be OWL-ViT's 512-d unit query embedding.
            assertEquals(512, it.embed.size)
            assertEquals(it.id, 1.0, Math.sqrt(it.embed.sumOf { v -> (v * v).toDouble() }), 1e-3)
        }
    }

    @Test fun `the model confirming a hazard makes it a fix`() {
        val f = Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric"))!!
        assertEquals(Status.FIX, f.status)
    }

    @Test fun `a protected object goes to the done list`() {
        val f = Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric", prot = true))!!
        assertEquals(Status.PROTECTED, f.status)
    }

    @Test fun `a critical hit the model rejects stays as check only when the detector was sure`() {
        assertEquals(Status.CHECK, Rules.decide(Candidate(socket, box, 0.25f, "whole"), answer("none"))!!.status)
        assertNull(Rules.decide(Candidate(socket, box, 0.15f, "whole"), answer("none")))
        assertNull(Rules.decide(Candidate(shelf, box, 0.9f, "whole"), answer("none")))
    }

    @Test fun `out of reach drops a choking hazard but not a tip-over one`() {
        assertNull(Rules.decide(Candidate(coins, box, 0.3f, "whole"), answer("choking", reach = false)))
        assertNotNull(Rules.decide(Candidate(shelf, box, 0.3f, "whole"), answer("tip-over", reach = false)))
    }

    @Test fun `without the model only strong detector hits are shown, as check`() {
        assertNull(Rules.decide(Candidate(socket, box, 0.12f, "whole"), null))
        assertEquals(Status.CHECK, Rules.decide(Candidate(socket, box, 0.2f, "whole"), null)!!.status)
    }

    @Test fun `the model's object name relabels within a category only`() {
        val strip = hazard("power_strip", "electric", true)
        val cat = listOf(socket, strip, shelf).associateBy { it.id }
        val f = Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric", obj = "an extension board with plugs"), cat)!!
        assertEquals("power_strip", f.hazard.id)
        // "shelf" is tip-over, not electric: the detector's category stands.
        assertEquals("socket", Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric", obj = "socket on a shelf"), cat)!!.hazard.id)
    }

    @Test fun `duplicates of one object merge`() {
        val a = Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric"))!!
        val b = Rules.decide(Candidate(socket, Box(0.11f, 0.11f, 0.21f, 0.21f), 0.2f, "top-left"), answer("electric"))!!
        assertEquals(1, Rules.merge(listOf(a, b)).size)
    }

    @Test fun `the room-wide look adds only hazards not already listed`() {
        val cat = listOf(socket, hazard("stairs", "fall", true)).associateBy { it.id }
        val found = listOf(Rules.decide(Candidate(socket, box, 0.3f, "whole"), answer("electric"))!!)
        val out = Rules.addScene(found, listOf(
            SceneItem("electrical outlet", "electric", "left"),
            SceneItem("staircase without a gate", "fall", "right"),
        ), cat)
        assertEquals(listOf("socket", "stairs"), out.map { it.hazard.id })
        assertEquals(Status.CHECK, out[1].status)
    }

    @Test fun `the crop form parses with stray formatting`() {
        val a = Prompts.parseCrop("**OBJECT:** wall socket\nHAZARD: Electrical shock\nREACHABLE: Yes.\nPROTECTED: no")!!
        assertEquals("electric", a.hazard); assertEquals(true, a.reachable); assertEquals(false, a.protected)
        assertEquals("wall socket", a.obj)
        assertNull(Prompts.parseCrop("I cannot tell."))
    }

    @Test fun `the scene list parses comma separated objects`() {
        val items = Prompts.parseScene("Gas stove, open window, 1. knife on the counter.")
        assertEquals(listOf("Gas stove", "open window", "knife on the counter"), items.map { it.thing })
        assertTrue(Prompts.parseScene("NONE").isEmpty())
    }

    @Test fun `the match form decides on agreement, protection and reach`() {
        fun m(match: Boolean, prot: Boolean = false, reach: Boolean = true, obj: String = "thing") =
            Prompts.parseCrop("OBJECT: $obj\nMATCH: ${if (match) "Yes" else "No"}\nPROTECTED: ${if (prot) "yes" else "no"}\nREACHABLE: ${if (reach) "yes" else "no"}")!!
        assertEquals(Status.FIX, Rules.decide(Candidate(socket, box, 0.13f, "whole"), m(true))!!.status)
        assertEquals(Status.PROTECTED, Rules.decide(Candidate(socket, box, 0.13f, "whole"), m(true, prot = true))!!.status)
        assertNull(Rules.decide(Candidate(socket, box, 0.13f, "whole"), m(false)))
        assertEquals(Status.CHECK, Rules.decide(Candidate(socket, box, 0.3f, "whole"), m(false))!!.status)
        assertNull(Rules.decide(Candidate(coins, box, 0.3f, "whole"), m(true, reach = false)))
        val strip = hazard("power_strip", "electric", true)
        val cat = listOf(socket, strip).associateBy { it.id }
        val f = Rules.decide(Candidate(socket, box, 0.13f, "whole"), m(false, obj = "white extension board"), cat)!!
        assertEquals("power_strip", f.hazard.id); assertEquals(Status.FIX, f.status)
    }

    @Test fun `tiles map back to the photo and nms keeps one box per object`() {
        val tile = Tiles.plan(1000, 800)[4] // bottom-right quarter
        val inPhoto = Tiles.toPhoto(tile.area, Box(0f, 0f, 0.5f, 0.5f))
        assertEquals(0.4f, inPhoto.l, 1e-4f); assertEquals(0.7f, inPhoto.r, 1e-4f)
        val kept = Tiles.nms(listOf(
            Candidate(socket, Box(0.1f, 0.1f, 0.2f, 0.2f), 0.3f, "whole"),
            Candidate(socket, Box(0.105f, 0.1f, 0.2f, 0.21f), 0.25f, "top-left"),
            Candidate(socket, Box(0.0f, 0.0f, 0.95f, 0.95f), 0.5f, "whole"), // the whole room is not a socket
        ))
        assertEquals(1, kept.size); assertEquals(0.3f, kept[0].score)
    }

    @Test fun `a yes that names a harmless thing is not believed`() {
        fun m(obj: String) = Prompts.parseCrop("OBJECT: $obj\nMATCH: Yes\nPROTECTED: no\nREACHABLE: yes")!!
        val lamp = hazard("lamp", "tip-over", false)
        assertNull(Rules.decide(Candidate(lamp, box, 0.5f, "whole"), m("sofa")))
        assertNull(Rules.decide(Candidate(coins, box, 0.15f, "whole"), m("A frying pan with a spatula")))
        // Naming a catalogue thing alongside wins: a socket on a table is still a socket.
        assertEquals(Status.FIX, Rules.decide(Candidate(socket, box, 0.15f, "whole"), m("socket on a table"))!!.status)
        // Whole words: an infant is not a fan.
        assertEquals(false, Rules.namesSomethingHarmless("infant toy"))
    }

    @Test fun `room words do not misread pillows, stools or curtains`() {
        val cat = listOf(hazard("medicine", "poison", true), hazard("plastic_bag", "choking", true),
                         hazard("blind_cord", "strangle", true)).associateBy { it.id }
        listOf("pillows", "plastic stool", "curtains").forEach {
            assertNull(it, Rules.sceneHazard(SceneItem(it, "", ""), cat))
        }
        assertEquals("medicine", Rules.sceneHazard(SceneItem("pills on the table", "", ""), cat)?.id)
    }

    @Test fun `severity puts sockets and stairs before plants`() {
        assertEquals(com.nila.babyproof.Severity.URGENT, socket.severity)
        assertEquals(com.nila.babyproof.Severity.URGENT, hazard("stairs", "fall", true).severity)
        assertEquals(com.nila.babyproof.Severity.HIGH, shelf.severity)
        assertEquals(com.nila.babyproof.Severity.MEDIUM, hazard("plant", "poison", false).severity)
    }
}
