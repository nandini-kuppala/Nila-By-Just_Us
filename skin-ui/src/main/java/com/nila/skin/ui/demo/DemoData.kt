package com.nila.skin.ui.demo

import com.nila.skin.core.color.Lab
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.Sign
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.StoolRules
import com.nila.skin.core.synth.Synth
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.Codec
import com.nila.skin.data.ConcernEntity
import com.nila.skin.data.EyeCheckEntity
import com.nila.skin.data.FoodLogEntity
import com.nila.skin.data.MealEntity
import com.nila.skin.data.PhotoStore
import com.nila.skin.data.ReactionEntity
import com.nila.skin.data.SkinEntryEntity
import com.nila.skin.data.StoolCheckEntity
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.screens.reasonText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Demo mode for judges.
 *
 * A made-up baby with a week of history, built on the phone in a few seconds.
 * The skin photos are synthetic (see [Synth]), and every number shown for them
 * -- size, redness, spot count, the trend and the next step -- is measured and
 * decided by the same code a real photo goes through, not typed in. Three
 * stories are set up so each part of the app has something to show:
 *
 *  - a cheek patch that is fading (calm: keep an eye on it)
 *  - tummy spots that are spreading fast (show your doctor this week)
 *  - egg followed twice by hives (a food pattern) and a new peanut check-in due
 */
object DemoData {

    private const val DAY = 86_400_000L
    private const val H = 3_600_000L

    suspend fun clear() = withContext(Dispatchers.IO) {
        val d = SkinServices.dao
        d.wipeEntries(); d.wipeConcerns(); d.wipeFoods(); d.wipeMeals(); d.wipeReactions(); d.wipeStools(); d.wipeEyes(); d.wipeYellows(); d.wipeBaby()
        SkinServices.photos.deleteAll()
    }

    suspend fun load() = withContext(Dispatchers.Default) {
        clear()
        val d = SkinServices.dao
        val now = System.currentTimeMillis()
        val ageDays = 213
        d.saveBaby(BabyEntity(name = "Aarav (demo)", birthMs = now - ageDays * DAY, demo = true))

        // ---- 1. Cheek patch, fading -------------------------------------------
        val tone4 = Synth.TONES[3].second
        val cheek = d.addConcern(ConcernEntity(title = "Cheek patch", kind = ConcernKind.DRY_PATCH.name, area = BodyArea.FACE.name, createdMs = now - 6 * DAY))
        val cheekDays = listOf(6, 4, 2, 0)
        val cheekR = listOf(0.15, 0.13, 0.10, 0.075)
        val cheekA = listOf(9.0, 7.5, 5.5, 4.8)
        cheekDays.forEachIndexed { i, day ->
            val img = Synth.skin(640, tone4, listOf(Synth.Spot(0.5, 0.5, cheekR[i])), deltaA = cheekA[i], deltaL = 7.0, seed = 10 + i)
            addEntry(cheek, ConcernKind.DRY_PATCH, BodyArea.FACE, ageDays, img, now - day * DAY - 3 * H, NCircle(0.5, 0.5, 0.25))
        }

        // ---- 2. Tummy spots, spreading ----------------------------------------
        val tone3 = Synth.TONES[2].second
        val tummy = d.addConcern(ConcernEntity(title = "Tummy spots", kind = ConcernKind.RASH.name, area = BodyArea.CHEST_BELLY.name, createdMs = now - 2 * DAY))
        val rnd = Random(42)
        val all = List(16) { Synth.Spot(0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.014 + rnd.nextDouble() * 0.012) }
        listOf(2 to 5, 1 to 10, 0 to 16).forEachIndexed { i, (day, n) ->
            val img = Synth.skin(640, tone3, all.take(n), deltaA = Synth.rednessFor(tone3, 15.0), deltaL = -3.0, seed = 20 + i)
            addEntry(tummy, ConcernKind.RASH, BodyArea.CHEST_BELLY, ageDays, img, now - day * DAY - 2 * H, NCircle(0.5, 0.5, 0.27))
        }

        // ---- 3. Birthmark, steady ---------------------------------------------
        val tone2 = Synth.TONES[1].second
        val mark = d.addConcern(ConcernEntity(title = "Mark on the arm", kind = ConcernKind.BIRTHMARK.name, area = BodyArea.ARMS_HANDS.name, createdMs = now - 21 * DAY))
        listOf(21 to 0.09, 7 to 0.092).forEachIndexed { i, (day, r) ->
            val img = Synth.skin(640, tone2, listOf(Synth.Spot(0.5, 0.5, r)), deltaA = 18.0, deltaL = -6.0, seed = 30 + i)
            addEntry(mark, ConcernKind.BIRTHMARK, BodyArea.ARMS_HANDS, ageDays, img, now - day * DAY, NCircle(0.5, 0.5, 0.2))
        }

        // ---- 4. Foods and reactions -------------------------------------------
        val daily = listOf("ragi_porridge" to 45.0, "khichdi" to 60.0, "banana" to 50.0, "curd" to 30.0)
        val logged = HashSet<String>()
        suspend fun log(food: String, grams: Double, t: Long, checked: Boolean = true) {
            val first = logged.add(food)
            d.addFood(FoodLogEntity(foodId = food, grams = grams, timeMs = t, firstTime = first, checkedInMs = if (first && checked) t + 3 * H else null))
        }
        for (day in 10 downTo 1) {
            val base = now - day * DAY
            daily.forEachIndexed { i, (f, g) -> log(f, g, base + (8 + i * 3) * H) }
            if (day % 2 == 0) log("carrot", 30.0, base + 13 * H)
            if (day % 3 == 0) log("dal", 45.0, base + 19 * H)
        }
        log("moong_dal", 45.0, now - 8 * DAY + 13 * H)
        // Egg three times; hives after the first two.
        listOf(9, 6, 3).forEach { day -> log("egg_yolk", 17.0, now - day * DAY + 10 * H) }
        val reactions = listOf(now - 9 * DAY + 11 * H, now - 6 * DAY + 11 * H + 30 * 60_000L)
        val allLogs = d.foodsNow()
        reactions.forEachIndexed { i, t ->
            val ev = ReactionEvent(t, setOf(Sign.HIVES, Sign.REDNESS))
            val past = reactions.take(i + 1).map { ReactionEvent(it, ev.signs) }
            val p = ReactionPatterns.analyse(allLogs.filter { it.timeMs <= t }.map { Exposure(it.foodId, it.timeMs, it.firstTime) }, past)
            val adv = AllergyRules.advise(ev, p) { SkinServices.foods[it]?.name ?: it }
            d.addReaction(ReactionEntity(timeMs = t, signs = Codec.list(ev.signs.map { it.name }), note = "Red bumps round the mouth", step = adv.step.name))
        }
        // Today: a plate and a first peanut, with its check-in still due.
        val meal = d.addMeal(MealEntity(timeMs = now - 3 * H))
        listOf("khichdi" to 75.0, "carrot" to 30.0, "curd" to 30.0).forEach { (f, g) ->
            d.addFood(FoodLogEntity(foodId = f, grams = g, timeMs = now - 3 * H, firstTime = false, mealId = meal))
        }
        log("peanut_butter", 5.0, now - 40 * 60_000L, checked = false)

        // ---- 5. Nappy and eye checks ------------------------------------------
        listOf(5 to "8", 3 to "B", 1 to "8").forEach { (day, id) ->
            val s = StoolCard.byId(id)
            val img = Synth.onNappy(400, s.lab, Synth.NEUTRAL, seed = day)
            val r = StoolCard.read(img, NCircle(0.5, 0.5, 0.15))
            val adv = StoolRules.advise(StoolRules.Input(s, r, ageDays))
            val path = SkinServices.photos.save(PhotoStore.toBitmap(img), "stool")
            d.addStool(StoolCheckEntity(timeMs = now - day * DAY, photoPath = path, chosen = id, measured = r.nearest.id,
                l = r.stool.l, a = r.stool.a, b = r.stool.b, whiteCorrected = r.whiteCorrected, step = adv.step.name))
        }
        val eye = Synth.eye(300, Lab(45.0, 58.0, 44.0))
        val pupil = NCircle(0.5, 0.5, 0.18)
        val pair = EyeReflex.readPair(eye, pupil, pupil)
        val eyeAdv = EyeRules.advise(EyeRules.Input(pair, 0, 0, emptyMap()))
        d.addEye(EyeCheckEntity(timeMs = now - 4 * DAY, photoPath = SkinServices.photos.save(PhotoStore.toBitmap(eye), "eye"),
            left = pair.left.reflex.name, right = pair.right.reflex.name, step = eyeAdv.step.name))
    }

    private suspend fun addEntry(concern: Long, kind: ConcernKind, area: BodyArea, ageDays: Int,
                                 img: com.nila.skin.core.image.PixelImage, time: Long, circle: NCircle) {
        val d = SkinServices.dao
        val r = SkinMeasure.measure(img, circle)
        val prev = d.entriesNow(concern)
        val points = prev.map { TrendPoint(it.timeMs, it.areaOfPhoto, false, it.redness, it.spots) } + TrendPoint(time, r.areaOfPhoto, false, r.rednessScore, r.spots)
        val trend = if (points.size >= 2) Trend.summarise(points) else null
        val answers = SkinRules.questions(kind).associate { it.id to Answer.NO }
        val adv = SkinRules.advise(SkinRules.Input(kind, area, ageDays, answers, r, trend, 1, prev.isEmpty()))
        val path = SkinServices.photos.save(PhotoStore.toBitmap(img), "skin")
        d.addEntry(SkinEntryEntity(
            concernId = concern, timeMs = time, photoPath = path, cx = circle.cx, cy = circle.cy, r = circle.r,
            coverage = r.coverage, areaOfPhoto = r.areaOfPhoto, areaMm2 = null, spots = r.spots,
            deltaA = r.deltaA, deltaL = r.deltaL, deltaB = r.deltaB, redness = r.rednessScore,
            words = Codec.list(r.words), answers = Codec.answers(answers), step = adv.step.name,
            reasons = Codec.list(adv.reasons.map { reasonText(it) }),
        ))
    }
}
