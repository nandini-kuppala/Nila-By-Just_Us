package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What the maths saw in one skin photo.
 *
 * Everything is measured **against the baby's own skin** in a ring around the
 * spot, never against a population average. That is a deliberate fairness
 * choice: dermatology models lose 27-36% of their accuracy on darker skin when
 * they learn "normal" from mostly light skin (Daneshjou et al., DDI dataset,
 * Science Advances 2022). A difference from the same child's skin a centimetre
 * away means the same thing on every skin tone.
 */
data class SkinReading(
    /** Share of the drawn circle that differs from the surrounding skin, 0..1. */
    val coverage: Double,
    /** Affected area as a share of the whole photo; comparable when framing matches. */
    val areaOfPhoto: Double,
    /** Affected area in mm2, only when a size reference (coin) was marked. */
    val areaMm2: Double?,
    val spots: Int,
    /** Largest single spot as a share of the circle. */
    val largestSpot: Double,
    /** Mean a* difference, affected minus own skin. Positive = redder. */
    val deltaA: Double,
    /** Mean L* difference. Negative = darker. */
    val deltaL: Double,
    /** Mean b* difference. Positive = yellower. */
    val deltaB: Double,
    val deltaE: Double,
    /** Erythema index difference, 100*log10(R/G) affected minus skin. */
    val deltaErythema: Double,
    val skin: Lab,
    val affected: Lab?,
    val words: List<String>,
    val overlay: Mask,
) {
    /** Redness on a 0-100 scale for charts: 0 = same as own skin, 100 = a*+25. */
    val rednessScore: Double get() = (deltaA / 25.0 * 100.0).coerceIn(0.0, 100.0)
}

/** A yes/no mask over a downscaled copy of the photo, for drawing the outline. */
class Mask(val width: Int, val height: Int, val bits: BooleanArray) {
    fun count() = bits.count { it }
}

/** A coin or sticker of known size marked on the photo. */
data class SizeReference(val circle: NCircle, val diameterMm: Double)

object SkinMeasure {

    private const val WORK_SIDE = 640
    private const val RING_SCALE = 1.7

    fun measure(
        photo: PixelImage,
        spot: NCircle,
        size: SizeReference? = null,
        wb: WhiteBalance = WhiteBalance.NONE,
    ): SkinReading {
        val img = photo.downscaled(WORK_SIDE)

        // 1. The baby's own skin: a ring round the spot, trimmed of anything
        //    that is not skin (a sleeve, the sheet) by dropping outliers twice.
        var ring = ArrayList<Lab>()
        Regions.forEachInRing(img, spot, RING_SCALE, 1) { x, y -> ring += wb.lab(img[x, y]) }
        if (ring.size < 30) Regions.forEachInRing(img, spot.copy(r = spot.r * 0.8), 2.4, 1) { x, y -> ring += wb.lab(img[x, y]) }
        require(ring.isNotEmpty()) { "the circle is outside the photo" }
        repeat(2) {
            val s = Regions.stats(ring)
            val spread = 1.4826 * sqrt(s.madL * s.madL + s.madA * s.madA + s.madB * s.madB)
            val keep = ring.filter { ColorMath.deltaE76(it, s.median) <= max(14.0, 3.0 * spread) }
            if (keep.size >= 20) ring = ArrayList(keep)
        }
        val skin = Regions.stats(ring)
        val spreadE = 1.4826 * sqrt(skin.madL * skin.madL + skin.madA * skin.madA + skin.madB * skin.madB)
        val tE = max(8.0, 3.5 * spreadE)
        val tA = max(4.5, 3.0 * 1.4826 * skin.madA)
        val skinEi = ringErythema(img, spot)

        // 2. Inside the circle: which pixels differ from that skin.
        val w = img.width; val h = img.height
        val raw = BooleanArray(w * h)
        val inside = BooleanArray(w * h)
        var circlePx = 0
        Regions.forEachInCircle(img, spot, 1) { x, y ->
            val i = y * w + x
            inside[i] = true
            circlePx++
            val lab = wb.lab(img[x, y])
            val dE = ColorMath.deltaE76(lab, skin.median)
            val dA = lab.a - skin.median.a
            if (dE > tE || dA > tA) raw[i] = true
        }
        val mask = open(raw, inside, w, h)

        // 3. Spots: connected groups big enough to be real.
        val minSize = max(6, (circlePx * 0.0008).toInt())
        val comps = components(mask, w, h, minSize)
        val keep = BooleanArray(w * h)
        comps.forEach { c -> c.forEach { keep[it] = true } }
        val affectedPx = comps.sumOf { it.size }

        var sl = 0.0; var sa = 0.0; var sb = 0.0; var se = 0.0; var sei = 0.0
        for (i in keep.indices) if (keep[i]) {
            val p = img.argb[i]
            val lab = wb.lab(p)
            sl += lab.l; sa += lab.a; sb += lab.b
            se += ColorMath.deltaE76(lab, skin.median)
            sei += ColorMath.erythemaIndex(wb.apply(p))
        }
        val affected = if (affectedPx > 0) Lab(sl / affectedPx, sa / affectedPx, sb / affectedPx) else null
        val dA = affected?.let { it.a - skin.median.a } ?: 0.0
        val dL = affected?.let { it.l - skin.median.l } ?: 0.0
        val dB = affected?.let { it.b - skin.median.b } ?: 0.0
        val dE = if (affectedPx > 0) se / affectedPx else 0.0
        val dEi = if (affectedPx > 0) sei / affectedPx - skinEi else 0.0

        val coverage = if (circlePx == 0) 0.0 else affectedPx.toDouble() / circlePx
        val areaOfPhoto = affectedPx.toDouble() / (w * h)
        val areaMm2 = size?.let {
            val pxPerMm = 2 * it.circle.radiusPx(img) / it.diameterMm
            affectedPx / (pxPerMm * pxPerMm)
        }
        return SkinReading(
            coverage = coverage,
            areaOfPhoto = areaOfPhoto,
            areaMm2 = areaMm2,
            spots = comps.size,
            largestSpot = if (circlePx == 0) 0.0 else (comps.maxOfOrNull { it.size } ?: 0).toDouble() / circlePx,
            deltaA = dA, deltaL = dL, deltaB = dB, deltaE = dE, deltaErythema = dEi,
            skin = skin.median, affected = affected,
            words = describe(coverage, comps.size, dA, dL, dB),
            overlay = Mask(w, h, keep),
        )
    }

    private fun ringErythema(img: PixelImage, spot: NCircle): Double {
        val v = ArrayList<Double>()
        Regions.forEachInRing(img, spot, RING_SCALE, 2) { x, y -> v += ColorMath.erythemaIndex(img[x, y]) }
        return Regions.med(v.sorted())
    }

    /**
     * Plain words for what the numbers mean. Descriptions only -- colour,
     * number and size -- the vocabulary a doctor uses to *describe* a rash,
     * never a name for one.
     */
    fun describe(coverage: Double, spots: Int, dA: Double, dL: Double, dB: Double): List<String> = buildList {
        if (coverage < 0.01 || spots == 0) {
            add("No clear difference from the skin around it")
            return@buildList
        }
        when {
            dA >= 12 -> add("Much redder than the skin around it")
            dA >= 4.5 -> add("Redder than the skin around it")
        }
        if (dA >= 4.5 && dB <= -3) add("A purplish tone")
        when {
            dL <= -8 -> add("Darker than the skin around it")
            dL >= 6 -> add("Lighter than the skin around it")
        }
        if (dB >= 7 && dA < 12) add("A yellowish tone")
        add(
            when {
                spots == 1 && coverage > 0.25 -> "One patch"
                spots == 1 -> "One spot"
                spots <= 5 -> "$spots separate spots"
                else -> "Many small spots ($spots)"
            }
        )
        add("Covers about ${(coverage * 100).toInt().coerceAtLeast(1)}% of the circle")
    }

    /** One erosion then one dilation, 3x3: removes speckle from skin texture. */
    private fun open(m: BooleanArray, inside: BooleanArray, w: Int, h: Int): BooleanArray {
        val er = BooleanArray(m.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!m[i]) continue
            var all = true
            loop@ for (dy in -1..1) for (dx in -1..1) if (!m[i + dy * w + dx]) { all = false; break@loop }
            er[i] = all
        }
        val di = BooleanArray(m.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!inside[i]) continue
            var any = false
            loop@ for (dy in -1..1) for (dx in -1..1) if (er[i + dy * w + dx]) { any = true; break@loop }
            di[i] = any && m[i]
        }
        return di
    }

    /** 8-connected components of at least [minSize] pixels. */
    fun components(m: BooleanArray, w: Int, h: Int, minSize: Int): List<IntArray> {
        val seen = BooleanArray(m.size)
        val out = ArrayList<IntArray>()
        val stack = IntArray(m.size)
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                members += i
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (m[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                }
            }
            if (members.size >= minSize) out += members.toIntArray()
        }
        return out
    }

    /** Area of a circle in px, for tests. */
    fun circleArea(img: PixelImage, c: NCircle): Double { val r = c.radiusPx(img); return PI * r * r }

    @Suppress("unused")
    private fun near(a: Double, b: Double, tol: Double) = abs(a - b) <= tol
}
