package com.nila.skin.core

import com.nila.skin.core.color.Lab
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.SpotFinder
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.StoolGroup
import com.nila.skin.core.measure.YellowReading
import com.nila.skin.core.measure.Yellowness
import com.nila.skin.core.synth.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The demo photos are real (Wikimedia Commons), so these tests run the maths
 * on real pictures, not only on synthetic ones with a known answer.
 */
class RealPhotoTest {

    private val demo = File("../skin-ui/src/main/assets/demo")

    private fun load(rel: String): PixelImage? {
        val f = File(demo, rel)
        if (!f.isFile) return null
        val im = ImageIO.read(f)
        return PixelImage(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width))
    }

    @Test fun realNormalStoolsReadNormal() {
        for (f in listOf("stool/real_yellow_seedy.jpg", "stool/real_transitional.jpg", "stool/real_brown.jpg")) {
            val img = load(f) ?: return
            val spot = StoolCard.suggestSpot(img) ?: NCircle(0.5, 0.5, 0.12)
            val r = StoolCard.read(img, spot)
            println("$f: spot $spot -> ${r.nearest.id} ${r.nearest.label} (${r.group}) L%.0f C%.0f white=${r.whiteCorrected}".format(r.stool.l, r.stool.chroma))
            assertTrue("$f read ${r.group}", r.group == StoolGroup.NORMAL)
        }
    }

    @Test fun eyeWhiteIsFoundOnRealAndSyntheticPhotos() {
        for (f in listOf("yellow/real_yellow_eye_whites.jpg", "yellow/real_yellow_eye_whites_2.jpg")) {
            val img = load(f) ?: return
            val c = Yellowness.suggestSpot(img)
            println("$f: eye white at $c")
            assertNotNull(c)
            val r = Yellowness.read(img, YellowReading.Site.EYE_WHITE, c!!)
            println("   -> b* %.1f, ${r.looks}, white found=${r.whiteCorrected} tinted=${r.whiteTinted}".format(r.yellowness))
        }
        // Synthetic eye with a known yellow white and a tissue: the circle must land on the white.
        val sclera = Lab(85.0, 1.0, 26.0)
        val img = Synth.skin(300, Lab(55.0, 12.0, 20.0), emptyList(), deltaA = 0.0, white = Synth.Spot(0.15, 0.15, 0.1))
        val px = img.argb.copyOf()
        for (y in 0 until 300) for (x in 0 until 300) {
            val dx = (x - 150) / 110.0; val dy = (y - 160) / 45.0
            if (dx * dx + dy * dy < 1) px[y * 300 + x] = com.nila.skin.core.color.ColorMath.toArgb(sclera)
            if ((x - 150) * (x - 150) + (y - 160) * (y - 160) < 35 * 35) px[y * 300 + x] = com.nila.skin.core.color.ColorMath.toArgb(Lab(25.0, 5.0, 10.0))
        }
        val eye = PixelImage(300, 300, px)
        val c = Yellowness.suggestSpot(eye)!!
        val r = Yellowness.read(eye, YellowReading.Site.EYE_WHITE, c)
        println("synthetic eye: circle $c b* ${r.yellowness}")
        assertEquals(26.0, r.yellowness, 4.0)
    }

    @Test fun realSkinPhotosMeasure() {
        for (f in listOf("skin/infantile_hemangioma.jpg", "skin/capillary_haemangioma.jpg", "skin/real_mongolian_spot.jpg",
            "skin/erytherma_toxicum_neonatorum.jpg", "skin/real_cradle_cap.jpg", "skin/real_milia.jpg", "skin/irritant_diaper_dermatitis.jpg")) {
            val img = load(f) ?: return
            val c = SpotFinder.suggest(img)
            val r = c?.let { SkinMeasure.measure(img, it) }
            println("$f: auto circle $c -> ${r?.words}")
        }
    }
}

class RealDemoCirclesTest {
    /** The demo baby's real photos are measured with these circles; each must show a clear mark. */
    @Test fun demoCirclesSitOnTheMarks() {
        val demo = java.io.File("../skin-ui/src/main/assets/demo")
        if (!demo.isDirectory) return
        for ((f, c) in listOf("skin/infantile_hemangioma.jpg" to NCircle(0.56, 0.47, 0.2), "skin/real_mongolian_spot.jpg" to NCircle(0.66, 0.43, 0.1),
            "skin/real_cradle_cap.jpg" to NCircle(0.62, 0.55, 0.24))) {
            val im = javax.imageio.ImageIO.read(java.io.File(demo, f))
            val r = SkinMeasure.measure(PixelImage(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width)), c)
            println("$f -> ${r.words} dA ${r.deltaA} dL ${r.deltaL}")
            println("   texture ratio ${r.textureRatio}")
            assertTrue("$f: ${r.words}", r.coverage > 0.05 || r.textureRatio >= SkinMeasure.ROUGH)
        }
    }
}
