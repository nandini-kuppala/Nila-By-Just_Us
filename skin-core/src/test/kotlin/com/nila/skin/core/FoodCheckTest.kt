package com.nila.skin.core

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.food.FoodCheck
import com.nila.skin.core.food.FoodDb
import com.nila.skin.core.food.Palette
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.vlm.VisionForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class FoodCheckTest {

    private val db = FoodDb.bundled()

    private fun bowl(food: Lab): PixelImage {
        val n = 200
        return PixelImage(n, n, IntArray(n * n) { i ->
            val x = i % n - n / 2; val y = i / n - n / 2
            ColorMath.toArgb(if (x * x + y * y < 60 * 60) food else Lab(92.0, 0.0, 2.0))
        })
    }

    @Test fun paletteFindsTheMainColours() {
        val p = Palette.of(bowl(Lab(62.0, 30.0, 58.0)))
        assertTrue(p.any { ColorMath.deltaE76(it.lab, Lab(62.0, 30.0, 58.0)) < 3 && it.share in 0.2..0.4 })
        assertTrue(p.any { ColorMath.deltaE76(it.lab, Lab(92.0, 0.0, 2.0)) < 3 && it.share > 0.55 })
    }

    @Test fun colourCanRuleAFoodOut() {
        val orange = Palette.of(bowl(Lab(62.0, 30.0, 58.0)))
        assertTrue(FoodCheck.colour("carrot", orange)!!.found)
        assertFalse(FoodCheck.colour("spinach", orange)!!.found)
        assertFalse(FoodCheck.colour("beetroot", orange)!!.found)
        assertNull(FoodCheck.colour("chicken", orange)) // no reference: the check is skipped, not failed
    }

    @Test fun verdicts() {
        fun ev(s: Boolean?, y: Boolean?, c: Boolean?) = FoodCheck.Evidence("x", null, s, y, c?.let { FoodCheck.ColourMatch(it, 0.1, 5.0) })
        assertEquals(FoodCheck.Verdict.CONFIRMED, ev(true, true, true).verdict)
        assertEquals(FoodCheck.Verdict.CONFIRMED, ev(true, true, null).verdict)
        assertEquals(FoodCheck.Verdict.LIKELY, ev(false, true, true).verdict)
        assertEquals(FoodCheck.Verdict.UNSURE, ev(false, true, false).verdict)
        assertEquals(FoodCheck.Verdict.UNSURE, ev(false, false, null).verdict)
        assertFalse(ev(true, false, false).keep)
    }

    @Test fun secondLookMatchesByFoodOrWords() {
        val second = VisionForms.parsePlate("mashed banana, oats", db)
        assertTrue(FoodCheck.named("banana", db.match("banana"), second))
        assertFalse(FoodCheck.named("apple", db.match("apple"), second))
    }

    /** The colour references are general, not tuned on the demo photos; check they behave on them. */
    @Test fun colourCheckOnTheRealDemoPhotos() {
        val dir = File("../skin-ui/src/main/assets/demo/meal")
        if (!dir.isDirectory) return
        fun pal(f: String): List<Palette.Cluster> {
            val im = ImageIO.read(File(dir, f))
            val w = im.width; val h = im.height
            return Palette.of(PixelImage(w, h, im.getRGB(0, 0, w, h, null, 0, w)))
        }
        val cases = mapOf(
            "pumpkin_puree.jpg" to (listOf("pumpkin") to listOf("spinach", "beetroot")),
            "pureed_veggies.jpg" to (listOf("carrot", "beetroot", "peas") to emptyList()),
            "ragi_porridge.jpg" to (listOf("ragi_porridge") to listOf("spinach", "beetroot", "carrot")),
            "mashed_banana.jpg" to (listOf("banana") to listOf("beetroot")),
            "suji_kheer.jpg" to (listOf("suji_kheer") to listOf("spinach", "peas", "beetroot")),
            "vegetable_khichdi_1.jpg" to (listOf("khichdi") to listOf("beetroot")),
        )
        for ((f, c) in cases) {
            val p = pal(f)
            println("$f: " + p.joinToString { "L%.0f a%.0f b%.0f %.0f%%".format(it.lab.l, it.lab.a, it.lab.b, it.share * 100) })
            c.first.forEach { food -> assertTrue("$f should contain $food's colour", FoodCheck.colour(food, p)!!.found) }
            c.second.forEach { food -> assertFalse("$f should not contain $food's colour", FoodCheck.colour(food, p)!!.found) }
        }
    }
}

class ColourProposalTest {
    @Test fun proposalsOnTheRealDemoPhotos() {
        val dir = java.io.File("../skin-ui/src/main/assets/demo/meal")
        if (!dir.isDirectory) return
        val got = dir.listFiles()!!.filter { it.name.endsWith(".jpg") }.sortedBy { it.name }.associate { f ->
            val im = javax.imageio.ImageIO.read(f)
            val px = com.nila.skin.core.food.FoodCheck.Pixels.of(com.nila.skin.core.image.PixelImage(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width)))
            f.name to com.nila.skin.core.food.FoodCheck.proposals(px, emptySet())
        }
        got.forEach { (f, p) -> println("$f -> " + p.joinToString { "${it.first} ${(it.second * 100).toInt()}%" }) }
        org.junit.Assert.assertTrue(got.getValue("ragi_porridge.jpg").any { it.first == "ragi_porridge" })
        // Whites and yellows are never proposed: they are too alike to name from colour.
        org.junit.Assert.assertTrue(got.values.flatten().none { it.first in setOf("idli", "khichdi", "rice", "oats", "banana", "suji_kheer") })
    }
}
