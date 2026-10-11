package com.nila.skin.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.food.oneIn
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.Mask
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.JaundiceRules
import com.nila.skin.core.rules.Reason
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.core.rules.StoolRules
import com.nila.skin.core.synth.Synth
import com.nila.skin.core.vlm.Agreement
import com.nila.skin.core.vlm.VisionForms
import com.nila.skin.data.PhotoStore
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.components.AdviceCard
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.MarkablePhoto
import com.nila.skin.ui.components.Marker
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.components.StepPill
import com.nila.skin.ui.safely
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.random.Random

/** One picture in a demo result, with what the maths drew on it. */
data class DemoImage(
    val bitmap: Bitmap, val label: String,
    val circles: List<NCircle> = emptyList(), val mask: Mask? = null, val dots: List<NPoint> = emptyList(),
)

/** A colour shown as a chip instead of a photo (used for nappies: Nila only needs the colour). */
data class DemoChip(val argb: Int, val label: String)

data class DemoResult(
    val images: List<DemoImage>, val facts: List<String>, val advice: Advice? = null, val takeaway: String,
    val chips: List<DemoChip> = emptyList(),
)

/** An everyday moment a parent has, and the code that answers it. */
data class DemoCase(
    val id: String, val title: String, val story: String, val shows: String,
    val route: String, val usesAi: Boolean = false, val run: suspend () -> DemoResult,
)

private const val DAY = 86_400_000L
private const val H = 3_600_000L

/**
 * The demo lab: everyday moments, one tap each.
 *
 * Little Signs is for the small things a parent wonders about between doctor
 * visits -- a big, obvious problem goes straight to the doctor, not into an
 * app. So most cases here end in "this is normal, here's why", and the few
 * that don't are the quiet, easy-to-miss signs (a pale nappy under a lamp, one
 * white pupil in a family photo). Each runs the same maths, rules and
 * on-device AI as the real screens, on real openly licensed photos where one
 * exists and on pictures with a known answer where the point is to prove the
 * maths.
 */
object DemoCases {

    private fun bmp(img: com.nila.skin.core.image.PixelImage) = PhotoStore.toBitmap(img)
    private fun calm(title: String, vararg doNow: String) = Advice(Step.WATCH, emptyList(), doNow.toList(), title = title)

    private fun stoolChips(img: com.nila.skin.core.image.PixelImage, spot: NCircle): Pair<StoolCard.Reading, List<DemoChip>> {
        val raw = StoolCard.read(img, spot, correct = false)
        val fixed = StoolCard.read(img, spot)
        return fixed to listOf(
            DemoChip(com.nila.skin.core.color.ColorMath.toArgb(raw.stool), "As photographed"),
            DemoChip(com.nila.skin.core.color.ColorMath.toArgb(fixed.stool), "Corrected by the white nappy"),
            DemoChip(fixed.nearest.argb, "Card colour ${fixed.nearest.id}: ${fixed.nearest.label}"),
        )
    }

    val all: List<DemoCase> = listOf(
        DemoCase("poo", "Is this poo colour normal?",
            "A 3-week-old's nappy, yellow and seedy. The parent isn't worried, just checking, as the card says to in the first month.",
            "Nila reads the colour against the white of the nappy and matches it to the Ontario stool card. It shows colours, not the nappy.", "stool") {
            val photo = SkinServices.photos.loadAsset("demo/stool/real_yellow_seedy.jpg")!!
            val img = PhotoStore.toPixels(photo)
            val (r, chips) = stoolChips(img, StoolCard.suggestSpot(img) ?: NCircle(0.5, 0.5, 0.12))
            DemoResult(emptyList(),
                listOf("Read from a real nappy photo (Wikimedia Commons): closest card colour ${r.nearest.id}, ${r.nearest.label.lowercase()}: a normal colour.",
                    "Yellow, mustard, green and brown are all normal. Only the pale colours (1-6) mean call the doctor."),
                StoolRules.advise(StoolRules.Input(r.nearest, r, 21)),
                "Five seconds a day in the first month. Almost every day the answer is \"normal\", and that is the point: the one day it isn't is easy to miss.",
                chips)
        },
        DemoCase("lamp", "The same check at night, under a lamp",
            "Another night nappy in a dim bedroom. It looks yellow-ish, so it feels fine. (A test pattern of card colour 5: no open photo of pale infant poo exists.)",
            "Correcting colour and brightness from the white nappy. A dim warm room makes pale poo look darker and more coloured.", "stool") {
            val img = Synth.onNappy(400, StoolCard.byId("5").lab, doubleArrayOf(0.4, 0.32, 0.22), seed = 21)
            val spot = NCircle(0.5, 0.5, 0.15)
            val raw = StoolCard.read(img, spot, correct = false)
            val (r, chips) = stoolChips(img, spot)
            DemoResult(emptyList(),
                listOf("As photographed it matches ${raw.nearest.id} (${raw.nearest.label.lowercase()}), which would look normal.",
                    "Corrected, it is ${r.nearest.id} (${r.nearest.label.lowercase()}): one of the pale colours."),
                StoolRules.advise(StoolRules.Input(r.nearest, r, 21)),
                "This is the quiet sign the card exists for. Taiwan's card raised 5-year survival in biliary atresia from 55.7% to 89.3%. In our dim-light test: 8 of 13 card colours right uncorrected, 13 of 13 corrected.",
                chips)
        },
        DemoCase("milia", "Tiny white spots on the nose",
            "A newborn has little white dots on the nose. Common, but a first-time parent wonders.",
            "The Common and harmless guide, with a real photo for what it can look like.", "learn") {
            val photo = SkinServices.photos.loadAsset("demo/skin/real_milia.jpg")!!
            DemoResult(listOf(DemoImage(photo, "Milia, real photo (Wikimedia Commons)")),
                listOf("Milia: tiny white or yellow spots, mostly on the nose and cheeks.", "They clear by themselves within a few weeks."),
                calm("Common and harmless", "Nothing to do; don't squeeze them.", "Mention them at the next check-up if they're still there after a few months."),
                "Most of what parents notice is one of these. Knowing that is what keeps a notice from becoming a fright.")
        },
        DemoCase("scalp", "Flaky patches on the scalp",
            "Yellowish flakes on a 2-month-old's head. The skin colour looks the same; it's the surface that's different.",
            "Colour against the baby's own skin, plus a texture measure (fine detail inside vs the skin around it).", "learn") {
            val photo = SkinServices.photos.loadAsset("demo/skin/real_cradle_cap.jpg")!!
            val circle = NCircle(0.62, 0.55, 0.24)
            val r = SkinMeasure.measure(PhotoStore.toPixels(photo), circle)
            DemoResult(listOf(DemoImage(photo, "Real photo (Wikimedia Commons)", listOf(circle))),
                r.words + listOf("Colour alone would miss this; texture shows it."),
                calm("Common and harmless", "Gentle washing and a soft brush help; it usually clears within weeks to months.", "See a doctor if it spreads, bleeds, weeps or seems itchy."),
                "Cradle cap is one of the commonest things parents ask about. The diary keeps a photo, so \"is it better?\" has an answer.")
        },
        DemoCase("mark", "A little red mark on the arm: the same, or bigger?",
            "A parent notices a small raised red mark and wants to keep an eye on it, not rush to a clinic.",
            "The ring around the circle is the baby's own skin; every pixel inside is compared with the skin expected there; the outline shows what was counted.", "skin") {
            val photo = SkinServices.photos.loadAsset("demo/skin/infantile_hemangioma.jpg")!!
            val circle = NCircle(0.56, 0.47, 0.2)
            val r = SkinMeasure.measure(PhotoStore.toPixels(photo), circle)
            val adv = SkinRules.advise(SkinRules.Input(ConcernKind.BIRTHMARK, BodyArea.ARMS_HANDS, 150,
                SkinRules.questions(ConcernKind.BIRTHMARK).associate { it.id to Answer.NO }, r, null, firstPhoto = true))
            DemoResult(listOf(DemoImage(photo, "Real photo, measured outline in blue", listOf(circle), r.overlay)),
                r.words + listOf("Redness against the baby's own skin: Δa* ${"%+.1f".format(r.deltaA)}; lightness ΔL* ${"%+.1f".format(r.deltaL)}.",
                    "With the next photo in a few days, the diary says whether it is the same, smaller or bigger."),
                adv,
                "Most such marks settle with time. A photo every few days turns \"has it grown?\" into a measured answer; only a mark that changes in the first months is seen early (AAP 2019).")
        },
        DemoCase("fading", "Red cheek patch: is it settling?",
            "Dry red cheeks for a week. The parent is moisturising and wants to know if it is helping. (Simulated photos: no open photo series of one baby exists.)",
            "Redness and size against the baby's own skin in each photo, and a robust trend (Theil–Sen) through all of them.", "skin") {
            val tone = Synth.TONES[3].second
            val circle = NCircle(0.5, 0.5, 0.25)
            val rs = listOf(0.15, 0.13, 0.10, 0.075).zip(listOf(9.0, 7.5, 5.5, 4.8)).mapIndexed { i, (rad, a) ->
                SkinMeasure.measure(Synth.skin(320, tone, listOf(Synth.Spot(0.5, 0.5, rad)), deltaA = a, deltaL = 7.0, seed = 10 + i), circle)
            }
            val t = Trend.summarise(rs.mapIndexed { i, r -> TrendPoint(i * 2 * DAY, r.areaOfPhoto, false, r.rednessScore, r.spots) })
            DemoResult(listOf(0, 3).map { i -> DemoImage(bmp(Synth.skin(240, tone, listOf(Synth.Spot(0.5, 0.5, listOf(0.15, 0.13, 0.10, 0.075)[i])), deltaA = listOf(9.0, 7.5, 5.5, 4.8)[i], deltaL = 7.0, seed = 10 + i)), if (i == 0) "Day 1" else "Day 7", listOf(circle)) },
                listOf(t.sentence) + (t.sizePerDay?.let { listOf("About ${"%.0f".format((1 - it) * 100)}% smaller per day.") } ?: emptyList()),
                calm("Settling: keep going", "Keep up the care you've started.", "A photo every few days is plenty now."),
                "Seeing it settle is as useful as seeing it spread: it tells a parent the care is working.")
        },
        DemoCase("tones", "Does it work on my baby's skin?",
            "The same red patch on six skin tones, from very light to very dark.",
            "Measuring against the baby's own skin in a ring around the spot, not a population average.", "how") {
            val spot = Synth.Spot(0.5, 0.5, 0.12); val circle = NCircle(0.5, 0.5, 0.22)
            val trueCov = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22)
            val rows = Synth.TONES.mapIndexed { i, (name, tone) ->
                val dA = Synth.rednessFor(tone, 14.0)
                val img = Synth.skin(320, tone, listOf(spot), deltaA = dA, seed = 300 + i)
                val r = SkinMeasure.measure(img, circle)
                Triple(name, img, "Tone $name: found ${"%.1f".format(r.coverage * 100)}% of the circle (true ${"%.1f".format(trueCov * 100)}%), ${r.spots} spot, redness Δa* ${"%.1f".format(r.deltaA)} (true ${"%.1f".format(dA)})")
            }
            DemoResult(listOf(0, 3, 5).map { DemoImage(bmp(rows[it].second), "Tone ${rows[it].first}", listOf(circle)) }, rows.map { it.third }, null,
                "Skin AI trained on mostly light skin loses 27-36% accuracy on dark skin (DDI, 2022). A difference from the same baby's skin a centimetre away means the same on every tone.")
        },
        DemoCase("light", "A photo taken by a window",
            "Light from one side makes half the arm brighter. The parent is tracking a flat brown mark.",
            "A plane fitted to the surrounding skin (least squares, outliers trimmed) gives the expected skin colour at every pixel.", "skin") {
            val tone = Synth.TONES[3].second
            val circle = NCircle(0.5, 0.5, 0.22)
            val img = Synth.skin(400, tone, listOf(Synth.Spot(0.5, 0.5, 0.12)), deltaA = 1.0, deltaL = -12.0, seed = 31, gradient = 0.4)
            val plane = SkinMeasure.measure(img, circle)
            val flat = SkinMeasure.measure(img, circle, lightingModel = false)
            DemoResult(
                listOf(DemoImage(bmp(img), "Lighting model: ${"%.0f".format(plane.coverage * 100)}% found", listOf(circle), plane.overlay),
                    DemoImage(bmp(img), "One flat skin colour: ${"%.0f".format(flat.coverage * 100)}% found", listOf(circle), flat.overlay)),
                listOf("True size: 30% of the circle, darker by 12 L*.",
                    "With the lighting model: ${"%.1f".format(plane.coverage * 100)}%, ${plane.spots} spot.",
                    "With one flat skin colour: ${"%.1f".format(flat.coverage * 100)}%: the light slope hides the mark."), null,
                "Real homes have windows. Same result on all six tones in the tests: the flat reference missed the mark every time, the plane found it every time.")
        },
        DemoCase("flash", "The family flash photo",
            "Flash photos happen anyway. Most show a red glow in both eyes; very rarely one shows white.",
            "MediaPipe face landmarks find the pupils (GPU), the reflex is read against the iris, and the gaze is checked for the off-angle false alarm.", "eyes") {
            val normal = SkinServices.photos.loadAsset("demo/eyes/red_eye_effect_2.jpg")!!
            val rare = SkinServices.photos.loadAsset("demo/eyes/coats_disease_camera_with_flash.jpg")!!
            fun read(b: Bitmap): Triple<com.nila.skin.core.measure.EyePairReading, com.nila.skin.core.measure.EyeAngle?, Bitmap> {
                val e = SkinServices.eyes.find(b)
                val l = e?.left ?: NCircle(0.4, 0.45, 0.03); val r = e?.right ?: NCircle(0.6, 0.45, 0.03)
                val crop = cropAround(b, NCircle((l.cx + r.cx) / 2, (l.cy + r.cy) / 2, kotlin.math.abs(r.cx - l.cx) * 0.8), 1.0)
                return Triple(EyeReflex.readPair(PhotoStore.toPixels(b, 1600), l, r), e?.angle, crop)
            }
            val (p1, a1, c1) = read(normal); val (p2, a2, c2) = read(rare)
            DemoResult(listOf(DemoImage(c1, "Usual: red glow"), DemoImage(c2, "Rare: white glow, one eye")),
                listOf("Usual photo: left ${p1.left.reflex.word.lowercase()}, right ${p1.right.reflex.word.lowercase()}. ${a1?.words() ?: ""}.",
                    "Rare photo: left ${p2.left.reflex.word.lowercase()}, right ${p2.right.reflex.word.lowercase()}. ${a2?.words() ?: ""}: Nila asks for 2-3 straight-on photos before anything else."),
                EyeRules.advise(EyeRules.Input(p1, 0, 0, emptyMap(), a1)),
                "Baylor's CRADLE study saw white-eye in family photos for 80% of affected children, about 1.3 years before diagnosis. Parents rarely notice it; a photo they already took can.")
        },
        DemoCase("cheeks", "Red cheeks after a new food: was it the food?",
            "Banana was new this morning; the cheeks went a little red that afternoon. Everything else was the usual.",
            "Exposure windows (2 h for skin redness, 48 h for eczema), the half rule, and Fisher's exact test against every other food.", "food") {
            val now = 20 * DAY
            val ex = ArrayList<Exposure>()
            for (d in 10 downTo 1) listOf("ragi_porridge", "khichdi", "curd").forEachIndexed { i, f -> ex += Exposure(f, now - d * DAY + (8 + i * 4) * H, d == 10) }
            ex += Exposure("banana", now - 1 * DAY + 10 * H, true)
            val re = listOf(ReactionEvent(now - 1 * DAY + 11 * H, setOf(Sign.REDNESS)))
            val p = ReactionPatterns.analyse(ex, re)
            val name: (String) -> String = { SkinServices.foods[it]?.name ?: it }
            DemoResult(emptyList(),
                p.filter { it.followed > 0 }.map { "${name(it.foodId)}: ${it.followed} of ${it.exposures} times followed → ${it.strength.name.lowercase().replace('_', ' ')}" } +
                    listOf("One reaction is not a pattern. If it happens again after the same food, Nila shows it, with how likely chance alone would be (egg after a fortnight of logs: 2 of 3, about 1 in 90).",
                        "Cheek redness from drool or wiping is common at this age too."),
                AllergyRules.advise(re.last(), p, name),
                "Parents often blame the last new food. Nila waits for evidence, because wrongly cutting out foods narrows a baby's diet.")
        },
        DemoCase("iron", "Is my baby getting enough iron?",
            "A typical week at 8 months: ragi, khichdi, curd, banana, a little dal and carrot.",
            "Food values from USDA FoodData Central against ICMR-NIN 2020 needs; WHO's eight food groups.", "meal") {
            val db = SkinServices.foods
            val day = listOf("ragi_porridge" to 45.0, "khichdi" to 60.0, "curd" to 30.0, "banana" to 50.0, "dal" to 30.0, "carrot" to 30.0)
            val items = day.mapNotNull { (id, g) -> db[id]?.let { com.nila.skin.core.food.PlateItem(it, g) } }
            val total = com.nila.skin.core.food.Nutrition.total(items)
            val t = com.nila.skin.core.food.Nutrition.targets(245)
            val groups = com.nila.skin.core.food.DietDiversity.of(items.map { it.food }, breastfedToday = true)
            val ideas = com.nila.skin.core.food.Nutrition.ideas(db, { it.ironMg }, 245)
            DemoResult(emptyList(),
                listOf("Iron from food: ${"%.1f".format(total.ironMg)} of ${"%.0f".format(t.ironMg)} mg a day.",
                    "Food groups: ${groups.count} of 8 (WHO's minimum is 5): ${if (groups.meets) "met" else "not yet met"}.",
                    "Iron-rich ideas from the table: " + ideas.joinToString { "${it.name.substringBefore(" (")} (${"%.1f".format(it.nutrientsFor(it.typicalGrams).ironMg)} mg)" } + ".",
                    "Pair them with vitamin C (guava, orange, tomato) to help absorption."), null,
                "Iron is the nutrient most often short at this age; India's NFHS-5 found about two in three children aged 6-59 months anaemic. A weekly view shows it without counting grams every day.")
        },
        DemoCase("plate", "What did my baby actually eat this week?",
            "A parent made three purées for the week and photographs the jars.",
            "Intern3.5-VL-2B on the NPU suggests foods; each is cross-checked three ways (two differently worded looks, a yes/no question, and colour maths with no AI); nutrients from USDA against ICMR-NIN 2020.", "meal", usesAi = true) {
            val photo = SkinServices.photos.loadAsset("demo/meal/pureed_veggies.jpg")!!
            val r = com.nila.skin.ui.PlateAI.look(photo)
                ?: return@DemoCase DemoResult(listOf(DemoImage(photo, "Jars of vegetable purée (Wikimedia Commons)")),
                    listOf("The on-device model isn't available on this phone, so the parent would add foods by hand. Everything else still works."), null,
                    "The app runs fully without the model.")
            val kept = r.evidence.filter { it.keep }
            DemoResult(listOf(DemoImage(photo, "Jars of vegetable purée (Wikimedia Commons)")),
                listOf("AI suggested ${r.evidence.size} foods in ${"%.1f".format(r.listMs / 1000.0)} s${if (r.listMs > 8000) " (includes loading the model)" else ""}; cross-checks took ${"%.1f".format(r.checkMs / 1000.0)} s.") +
                    r.evidence.map { "${it.food?.name ?: it.text}: ${if (it.fromColour) "suggested by colour" else it.verdict.label.lowercase()} — ${it.summary()}" } +
                    listOf("Pre-ticked for the parent: ${kept.joinToString { it.food?.name ?: it.text }.ifEmpty { "none" }}."),
                null,
                "The AI only saves typing. Three independent checks decide what is pre-ticked, and the parent confirms. Measured on ten real baby-food photos: How Nila decides → Test the AI.")
        },
        DemoCase("ai", "The AI says \"blisters\"; the parent looks closely",
            "The on-device AI describes possible blisters on a red patch. The parent looks and sees none.",
            "The AI describes and is compared with the maths; it can prompt a closer look but can't change the step. The parent's answer decides.", "skin") {
            val tone = Synth.TONES[2].second
            val circle = NCircle(0.5, 0.5, 0.22)
            val r = SkinMeasure.measure(Synth.skin(320, tone, listOf(Synth.Spot(0.5, 0.5, 0.12)), deltaA = 12.0, seed = 9), circle)
            val ai = VisionForms.parseSkin("COLOUR: red\nSURFACE: raised bumps, blisters\nPATTERN: patch\nPHOTO: clear")
            val base = SkinRules.questions(ConcernKind.RASH).associate { it.id to Answer.NO }
            val no = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.ARMS_HANDS, 200, base, r, null, firstPhoto = true))
            val yes = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.ARMS_HANDS, 200, base + ("blisters" to Answer.YES), r, null, firstPhoto = true))
            val agree = Agreement.skin(r, ai)
            DemoResult(emptyList(),
                listOf("AI description: colour ${ai.colour}, surface ${ai.surfaces.joinToString { it.label.lowercase() }}, pattern ${ai.pattern}.",
                    "${agree.level.title}: ${agree.points.joinToString(" ")}",
                    "Parent answers no → ${no.heading}. Parent answers yes → ${yes.heading} (${yes.reasons.first().source?.short})."),
                no,
                "A 2-billion-parameter model on a phone will sometimes be wrong. Here that can only cost a closer look, never a false alarm or a missed one.")
        },
        DemoCase("yellow", "Day 5: the eyes look a little yellow",
            "A 5-day-old's eyes look slightly yellow in daylight. Palms and soles look normal, and the baby is feeding well.",
            "The WHO/India IMNCI young-infant jaundice rows, answered by the parent. No bilirubin guess from the camera.", "yellow") {
            val adv = JaundiceRules.advise(JaundiceRules.Input(5 * 24 + 3, mapOf("eyes" to Answer.YES, "palms" to Answer.NO, "sleepy" to Answer.NO, "feeding" to Answer.NO), true, emptyList()))
            DemoResult(emptyList(),
                listOf("Newborn jaundice is common in the first week and usually fades by itself.",
                    "The one change to watch for: if the palms or soles turn yellow, the same checklist says see a doctor now."),
                adv,
                "A daily look in daylight, with the eye-white photo kept as a colour-corrected record, turns a worry into a routine.")
        },
    )
}

@Composable
fun DemoLabScreen(go: (String) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, DemoResult?>() }
    val running = remember { mutableStateMapOf<String, Boolean>() }
    var all by remember { mutableStateOf(false) }

    suspend fun run(c: DemoCase) {
        running[c.id] = true
        results[c.id] = safely("demo ${c.id}") { withContext(Dispatchers.Default) { c.run() } }
            ?: DemoResult(emptyList(), listOf("This demo couldn't run on this phone."), null, "")
        running[c.id] = false
    }

    SkinScaffold("Demo lab", onBack = back) {
        ScreenHero("Everyday moments, one tap each", "The little things parents wonder about between doctor visits. Most end in \"this is normal, here's why\"; a few are the quiet signs that are easy to miss.")
        PillButton(if (all) "Running..." else "Run every offline demo", enabled = !all, icon = Icons.Outlined.PlayArrow, modifier = Modifier.fillMaxWidth(), onClick = {
            all = true
            scope.launch { DemoCases.all.filter { !it.usesAi }.forEach { run(it) }; all = false }
        })
        DemoCases.all.forEachIndexed { i, c ->
            NilaCard(modifier = Modifier.fillMaxWidth(), tone = if (c.usesAi) Tone.BLUE else null) {
                Text("${i + 1}. ${c.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(c.story, style = MaterialTheme.typography.bodyMedium)
                MutedText("How: ${c.shows}")
                val r = results[c.id]
                if (running[c.id] == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    MutedText(if (c.usesAi) "Running on the NPU (the first run loads the model, 10-20 s)..." else "Running...")
                } else if (r == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Run", icon = if (c.usesAi) Icons.Outlined.AutoAwesome else Icons.Outlined.Science, onClick = { scope.launch { run(c) } }, modifier = Modifier.weight(1f))
                    OutlinePillButton("Open screen", icon = Icons.Outlined.ArrowForward, onClick = { go(c.route) }, modifier = Modifier.weight(1f))
                }
                r?.let { DemoResultView(it) }
                if (r != null) OutlinePillButton("Try it on the real screen", icon = Icons.Outlined.ArrowForward, onClick = { go(c.route) }, modifier = Modifier.fillMaxWidth())
            }
        }
        InfoNote("Real photos are from Wikimedia Commons, credited in the app's demo folder. Simulated pictures are labelled; they test the maths against a known answer and are not clinical validation.", tone = Tone.SLATE)
    }
}

@Composable
private fun DemoResultView(r: DemoResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.images.forEach { im ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkablePhoto(im.bitmap, im.circles.map { Marker(it, Color(0xFF3DDBB0), "") }, -1, onMove = { _, _ -> }, mask = im.mask, showSlider = false, dots = im.dots)
                    Text(im.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (r.chips.isNotEmpty()) ColourChips(r.chips)
        r.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        r.advice?.let { AdviceCard(it) }
        if (r.takeaway.isNotBlank()) InfoNote(r.takeaway, tone = Tone.TEAL, title = "Why it matters")
    }
}

/** Colours side by side: what the camera saw, what it really is, and the card colour. */
@Composable
fun ColourChips(chips: List<DemoChip>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        chips.forEach { c ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(64.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color(c.argb)))
                Text(c.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
