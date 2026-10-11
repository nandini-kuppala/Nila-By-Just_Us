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
 * A made-up baby with a week of history, built on the phone in a few seconds,
 * from real, openly licensed photos wherever one exists (Wikimedia Commons,
 * credited in assets/demo/CREDITS.md). Every number shown -- size, redness,
 * stool colour, eye reflex, the trend and the next step -- is measured and
 * decided by the same code a parent's photo goes through, not typed in.
 *
 *  - real photos: a strawberry mark, a Mongolian blue spot, cradle cap, two
 *    normal stools, a flash photo with red reflexes
 *  - one simulated series, labelled as such: tummy spots spreading over three
 *    days (no open photo series of one baby's rash exists), to show the trend
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

        // ---- 1-3. Real photos, one check each ---------------------------------
        realEntry("Strawberry mark on the arm", ConcernKind.BIRTHMARK, BodyArea.ARMS_HANDS, ageDays, "demo/skin/infantile_hemangioma.jpg",
            NCircle(0.56, 0.47, 0.2), now - 9 * DAY)
        realEntry("Blue-grey patch on the lower back", ConcernKind.BIRTHMARK, BodyArea.BACK, ageDays, "demo/skin/real_mongolian_spot.jpg",
            NCircle(0.66, 0.43, 0.1), now - 20 * DAY)
        realEntry("Flaky scalp", ConcernKind.DRY_PATCH, BodyArea.SCALP, ageDays, "demo/skin/real_cradle_cap.jpg",
            NCircle(0.62, 0.55, 0.24), now - 5 * DAY)

        // ---- 4. Tummy spots, spreading (simulated photos) ----------------------
        val tone3 = Synth.TONES[2].second
        val tummy = d.addConcern(ConcernEntity(title = "Tummy spots (simulated photos)", kind = ConcernKind.RASH.name, area = BodyArea.CHEST_BELLY.name, createdMs = now - 2 * DAY))
        val rnd = Random(42)
        val all = List(16) { Synth.Spot(0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.014 + rnd.nextDouble() * 0.012) }
        listOf(2 to 5, 1 to 10, 0 to 16).forEachIndexed { i, (day, n) ->
            val img = Synth.skin(640, tone3, all.take(n), deltaA = Synth.rednessFor(tone3, 15.0), deltaL = -3.0, seed = 20 + i)
            addEntry(tummy, ConcernKind.RASH, BodyArea.CHEST_BELLY, ageDays, img, now - day * DAY - 2 * H, NCircle(0.5, 0.5, 0.27))
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

        // ---- 5. Nappy and eye checks, real photos -------------------------------
        listOf(5 to "demo/stool/real_yellow_seedy.jpg", 2 to "demo/stool/real_brown.jpg").forEach { (day, asset) ->
            val bmp = SkinServices.photos.loadAsset(asset) ?: return@forEach
            val img = PhotoStore.toPixels(bmp)
            val r = StoolCard.read(img, StoolCard.suggestSpot(img) ?: NCircle(0.5, 0.5, 0.12))
            val adv = StoolRules.advise(StoolRules.Input(r.nearest, r, ageDays))
            d.addStool(StoolCheckEntity(timeMs = now - day * DAY, photoPath = SkinServices.photos.save(bmp, "stool"), chosen = r.nearest.id, measured = r.nearest.id,
                l = r.stool.l, a = r.stool.a, b = r.stool.b, whiteCorrected = r.whiteCorrected, step = adv.step.name))
        }
        SkinServices.photos.loadAsset("demo/eyes/red_eye_effect_2.jpg")?.let { eye ->
            val found = runCatching { SkinServices.eyes.find(eye) }.getOrNull()
            val pair = EyeReflex.readPair(PhotoStore.toPixels(eye, 1600), found?.left ?: NCircle(0.4, 0.45, 0.03), found?.right ?: NCircle(0.6, 0.45, 0.03))
            val eyeAdv = EyeRules.advise(EyeRules.Input(pair, 0, 0, emptyMap(), found?.angle))
            d.addEye(EyeCheckEntity(timeMs = now - 4 * DAY, photoPath = SkinServices.photos.save(eye, "eye"),
                left = pair.left.reflex.name, right = pair.right.reflex.name, step = eyeAdv.step.name))
        }
    }

    // ---- Samples inside one check, keeping the parent's own baby -------------
    //
    // Demo mode above replaces everything with a made-up baby. Inside Nila the
    // baby is real, so each check can instead show a few labelled samples, built
    // through the same measuring code: the skin diary with spots tracked over
    // days, the eye check with earlier flash photos.

    private suspend fun ageDaysNow(now: Long): Int =
        SkinServices.dao.babyNow()?.let { ((now - it.birthMs) / DAY).toInt().coerceAtLeast(1) } ?: 120

    /** Real photos of three common marks, plus two simulated series (one spreading, one settling) to show the trend. */
    suspend fun loadSkinSamples() = withContext(Dispatchers.Default) {
        val d = SkinServices.dao
        if (d.sampleConcernCount() > 0) return@withContext
        val now = System.currentTimeMillis()
        val ageDays = ageDaysNow(now)
        realEntry("Strawberry mark (sample)", ConcernKind.BIRTHMARK, BodyArea.ARMS_HANDS, ageDays, "demo/skin/infantile_hemangioma.jpg",
            NCircle(0.56, 0.47, 0.2), now - 9 * DAY)
        realEntry("Blue-grey patch (sample)", ConcernKind.BIRTHMARK, BodyArea.BACK, ageDays, "demo/skin/real_mongolian_spot.jpg",
            NCircle(0.66, 0.43, 0.1), now - 20 * DAY)
        realEntry("Flaky scalp (sample)", ConcernKind.DRY_PATCH, BodyArea.SCALP, ageDays, "demo/skin/real_cradle_cap.jpg",
            NCircle(0.62, 0.55, 0.24), now - 5 * DAY)
        val tone = Synth.TONES[2].second
        // Spreading: more spots each day.
        val tummy = d.addConcern(ConcernEntity(title = "Tummy spots (sample, simulated)", kind = ConcernKind.RASH.name, area = BodyArea.CHEST_BELLY.name, createdMs = now - 2 * DAY))
        val rnd = Random(42)
        val spots = List(16) { Synth.Spot(0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.014 + rnd.nextDouble() * 0.012) }
        listOf(2 to 5, 1 to 10, 0 to 16).forEachIndexed { i, (day, n) ->
            val img = Synth.skin(640, tone, spots.take(n), deltaA = Synth.rednessFor(tone, 15.0), deltaL = -3.0, seed = 20 + i)
            addEntry(tummy, ConcernKind.RASH, BodyArea.CHEST_BELLY, ageDays, img, now - day * DAY - 2 * H, NCircle(0.5, 0.5, 0.27))
        }
        // Settling: the same patch fading over five days.
        val cheek = d.addConcern(ConcernEntity(title = "Cheek redness (sample, simulated)", kind = ConcernKind.RASH.name, area = BodyArea.FACE.name, createdMs = now - 5 * DAY))
        val rnd2 = Random(7)
        val patch = List(12) { Synth.Spot(0.5 + (rnd2.nextDouble() - 0.5) * 0.3, 0.5 + (rnd2.nextDouble() - 0.5) * 0.3, 0.02 + rnd2.nextDouble() * 0.014) }
        listOf(Triple(5, 12, 16.0), Triple(3, 8, 11.0), Triple(1, 4, 7.0)).forEachIndexed { i, (day, n, red) ->
            val img = Synth.skin(640, tone, patch.take(n), deltaA = Synth.rednessFor(tone, red), deltaL = -2.0, seed = 40 + i)
            addEntry(cheek, ConcernKind.RASH, BodyArea.FACE, ageDays, img, now - day * DAY - 3 * H, NCircle(0.5, 0.5, 0.26))
        }
    }

    suspend fun clearSkinSamples() = withContext(Dispatchers.IO) {
        SkinServices.dao.wipeSampleEntries(); SkinServices.dao.wipeSampleConcerns()
    }

    /** Three earlier flash photos (real, Wikimedia Commons), read and decided as if taken over two weeks. */
    suspend fun loadEyeSamples() = withContext(Dispatchers.Default) {
        val d = SkinServices.dao
        val now = System.currentTimeMillis()
        var whiteLeft = 0; var whiteRight = 0
        listOf(14 to "demo/eyes/red_eye_effect_2.jpg", 6 to "demo/eyes/a_child_with_a_white_eye_reflection_as_a.jpg",
               2 to "demo/eyes/coats_disease_camera_with_flash.jpg").forEach { (day, asset) ->
            val eye = SkinServices.photos.loadAsset(asset) ?: return@forEach
            val found = runCatching { SkinServices.eyes.find(eye) }.getOrNull()
            val pair = EyeReflex.readPair(PhotoStore.toPixels(eye, 1600), found?.left ?: NCircle(0.4, 0.45, 0.03), found?.right ?: NCircle(0.6, 0.45, 0.03))
            if (pair.left.reflex == com.nila.skin.core.measure.Reflex.WHITE) whiteLeft++
            if (pair.right.reflex == com.nila.skin.core.measure.Reflex.WHITE) whiteRight++
            val adv = EyeRules.advise(EyeRules.Input(pair, whiteLeft - (if (pair.left.reflex == com.nila.skin.core.measure.Reflex.WHITE) 1 else 0),
                whiteRight - (if (pair.right.reflex == com.nila.skin.core.measure.Reflex.WHITE) 1 else 0), emptyMap(), found?.angle))
            d.addEye(EyeCheckEntity(timeMs = now - day * DAY, photoPath = SkinServices.photos.save(eye, "eye-sample"),
                left = pair.left.reflex.name, right = pair.right.reflex.name, step = adv.step.name))
        }
    }

    suspend fun clearEyeSamples() = withContext(Dispatchers.IO) { SkinServices.dao.wipeSampleEyes() }

    /** One check of a real demo photo, measured and decided like a parent's photo. */
    private suspend fun realEntry(title: String, kind: ConcernKind, area: BodyArea, ageDays: Int, asset: String, circle: NCircle, time: Long) {
        val bmp = SkinServices.photos.loadAsset(asset) ?: return
        val concern = SkinServices.dao.addConcern(ConcernEntity(title = title, kind = kind.name, area = area.name, createdMs = time))
        addEntry(concern, kind, area, ageDays, PhotoStore.toPixels(bmp), time, circle, bmp)
    }

    private suspend fun addEntry(concern: Long, kind: ConcernKind, area: BodyArea, ageDays: Int,
                                 img: com.nila.skin.core.image.PixelImage, time: Long, circle: NCircle, original: android.graphics.Bitmap? = null) {
        val d = SkinServices.dao
        val r = SkinMeasure.measure(img, circle)
        val prev = d.entriesNow(concern)
        val points = prev.map { TrendPoint(it.timeMs, it.areaOfPhoto, false, it.redness, it.spots) } + TrendPoint(time, r.areaOfPhoto, false, r.rednessScore, r.spots)
        val trend = if (points.size >= 2) Trend.summarise(points) else null
        val answers = SkinRules.questions(kind).associate { it.id to Answer.NO }
        val adv = SkinRules.advise(SkinRules.Input(kind, area, ageDays, answers, r, trend, 2, prev.isEmpty()))
        val path = SkinServices.photos.save(original ?: PhotoStore.toBitmap(img), "skin")
        d.addEntry(SkinEntryEntity(
            concernId = concern, timeMs = time, photoPath = path, cx = circle.cx, cy = circle.cy, r = circle.r,
            coverage = r.coverage, areaOfPhoto = r.areaOfPhoto, areaMm2 = null, spots = r.spots,
            deltaA = r.deltaA, deltaL = r.deltaL, deltaB = r.deltaB, redness = r.rednessScore,
            words = Codec.list(r.words), answers = Codec.answers(answers), step = adv.step.name,
            reasons = Codec.list(adv.reasons.map { reasonText(it) }),
        ))
    }
}
