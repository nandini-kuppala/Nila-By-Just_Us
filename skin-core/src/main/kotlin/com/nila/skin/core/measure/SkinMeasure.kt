package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance
import com.nila.skin.core.image.LabPlane
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

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
    /** How much L* changes across one circle radius in the surrounding skin: uneven light. */
    val lightSlope: Double = 0.0,
    /**
     * Fine texture inside the circle against the baby's own skin in the ring:
     * mean |Laplacian| of brightness, inside / ring. About 1 for smooth skin;
     * scales, crusts and bumps raise it. Colour can miss what texture shows.
     */
    val textureRatio: Double = 1.0,
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

    /**
     * @param lightingModel true (the default) compares each pixel with the skin
     *   colour expected at that point from a plane fitted to the ring, which
     *   cancels uneven light. False uses one median for the whole ring: the
     *   older, simpler reference, kept so the demo lab can show the difference.
     */
    fun measure(
        photo: PixelImage,
        spot: NCircle,
        size: SizeReference? = null,
        wb: WhiteBalance = WhiteBalance.NONE,
        lightingModel: Boolean = true,
    ): SkinReading {
        val img = photo.downscaled(WORK_SIDE)

        // 1. The baby's own skin: a ring round the spot, trimmed of anything
        //    that is not skin (a sleeve, the sheet), and modelled as a gentle
        //    slope so uneven light does not look like a difference.
        var ring = LabPlane.ringSamples(img, spot, RING_SCALE) { wb.lab(it) }
        if (ring.third.size < 30) ring = LabPlane.ringSamples(img, spot.copy(r = spot.r * 0.8), 2.4) { wb.lab(it) }
        require(ring.third.isNotEmpty()) { "the circle is outside the photo" }
        val (cxPx, cyPx) = spot.centrePx(img)
        val plane = LabPlane.fit(ring.first, ring.second, ring.third, cxPx, cyPx, spot.radiusPx(img).coerceAtLeast(1.0), flat = !lightingModel)
        val tE = max(8.0, 3.5 * plane.spread)
        val tA = max(4.5, 3.0 * plane.spreadA)
        val skinEi = ringErythema(img, spot)
        val textureRatio = texture(img, spot)

        // 2. Inside the circle: which pixels differ from the skin expected there.
        val w = img.width; val h = img.height
        val raw = BooleanArray(w * h)
        val inside = BooleanArray(w * h)
        var circlePx = 0
        Regions.forEachInCircle(img, spot, 1) { x, y ->
            val i = y * w + x
            inside[i] = true
            circlePx++
            val lab = wb.lab(img[x, y])
            val e = plane.at(x.toDouble(), y.toDouble())
            if (ColorMath.deltaE76(lab, e) > tE || lab.a - e.a > tA) raw[i] = true
        }
        val mask = open(raw, inside, w, h)

        // 3. Spots: connected groups big enough to be real.
        val minSize = max(6, (circlePx * 0.0008).toInt())
        val comps = components(mask, w, h, minSize)
        val keep = BooleanArray(w * h)
        comps.forEach { c -> c.forEach { keep[it] = true } }
        val affectedPx = comps.sumOf { it.size }

        // Differences are taken pixel by pixel against the local expected skin.
        var sl = 0.0; var sa = 0.0; var sb = 0.0; var se = 0.0; var sei = 0.0
        for (i in keep.indices) if (keep[i]) {
            val p = img.argb[i]
            val lab = wb.lab(p)
            val e = plane.at((i % w).toDouble(), (i / w).toDouble())
            sl += lab.l - e.l; sa += lab.a - e.a; sb += lab.b - e.b
            se += ColorMath.deltaE76(lab, e)
            sei += ColorMath.erythemaIndex(wb.apply(p))
        }
        val skin = plane.centre
        val dA = if (affectedPx > 0) sa / affectedPx else 0.0
        val dL = if (affectedPx > 0) sl / affectedPx else 0.0
        val dB = if (affectedPx > 0) sb / affectedPx else 0.0
        val affected = if (affectedPx > 0) Lab(skin.l + dL, skin.a + dA, skin.b + dB) else null
        val dE = if (affectedPx > 0) se / affectedPx else 0.0
        // The erythema index is a ratio of red to green, so a brightness slope
        // cancels in it without a model.
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
            skin = skin, affected = affected,
            words = describe(coverage, comps.size, dA, dL, dB,
                largest = if (circlePx == 0) 0.0 else (comps.maxOfOrNull { it.size } ?: 0).toDouble() / circlePx, texture = textureRatio),
            overlay = Mask(w, h, keep),
            lightSlope = plane.lightSlope,
            textureRatio = textureRatio,
        )
    }

    /** Mean |4-neighbour Laplacian| of grey level inside the circle, over the same in the ring. */
    private fun texture(img: PixelImage, spot: NCircle): Double {
        val w = img.width; val h = img.height
        fun grey(x: Int, y: Int): Double { val p = img[x, y]; return 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF) }
        fun lap(x: Int, y: Int): Double? = if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) null
            else kotlin.math.abs(4 * grey(x, y) - grey(x - 1, y) - grey(x + 1, y) - grey(x, y - 1) - grey(x, y + 1))
        var si = 0.0; var ni = 0; var sr = 0.0; var nr = 0
        Regions.forEachInCircle(img, spot.copy(r = spot.r * 0.85), 1) { x, y -> lap(x, y)?.let { si += it; ni++ } }
        Regions.forEachInRing(img, spot, RING_SCALE, 1) { x, y -> lap(x, y)?.let { sr += it; nr++ } }
        if (ni == 0 || nr == 0) return 1.0
        return (si / ni) / (sr / nr).coerceAtLeast(0.5)
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
    fun describe(coverage: Double, spots: Int, dA: Double, dL: Double, dB: Double, largest: Double = 0.0, texture: Double = 1.0): List<String> = buildList {
        val rough = texture >= ROUGH
        if (coverage < 0.01 || spots == 0) {
            add(if (rough) "No clear colour difference from the skin around it" else "No clear difference from the skin around it")
            if (rough) add("Rougher texture than the skin around it (about ${"%.1f".format(texture)}× the fine detail)")
            return@buildList
        }
        when {
            dA >= 12 -> add("Much redder than the skin around it")
            dA >= 4.5 -> add("Redder than the skin around it")
        }
        if (dA >= 4.5 && dB <= -3) add("A purplish tone")
        if (dA <= -4 && dL <= -6) add("A blue-grey tone")
        when {
            dL <= -8 -> add("Darker than the skin around it")
            dL >= 6 -> add("Lighter than the skin around it")
        }
        if (dB >= 7 && dA < 12) add("A yellowish tone")
        // One patch holding 80% or more of the change, with small dots beside it, is one patch.
        val dominant = spots > 1 && largest >= 0.8 * coverage && coverage > 0.25
        add(
            when {
                dominant -> "One patch, with ${spots - 1} small spot${if (spots == 2) "" else "s"} nearby"
                spots == 1 && coverage > 0.25 -> "One patch"
                spots == 1 -> "One spot"
                spots <= 5 -> "$spots separate spots"
                else -> "Many small spots ($spots)"
            }
        )
        add("Covers about ${(coverage * 100).toInt().coerceAtLeast(1)}% of the circle")
        if (rough) add("Rougher texture than the skin around it (about ${"%.1f".format(texture)}× the fine detail)")
    }

    /** Texture ratio above which "rougher" is said; smooth synthetic skin with a flat patch stays near 1. */
    const val ROUGH = 1.8

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
