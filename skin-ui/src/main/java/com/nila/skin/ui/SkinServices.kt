package com.nila.skin.ui

import android.content.Context
import com.nila.skin.ai.FaceEyes
import com.nila.skin.ai.NpuVisionModel
import com.nila.skin.ai.VisionModel
import com.nila.skin.core.food.FoodDb
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.PhotoStore
import com.nila.skin.data.SkinDao
import com.nila.skin.data.SkinDatabase

/**
 * Everything the screens need, created once.
 *
 * The seam for Nila: a host calls [init] with its own [VisionModel] (an
 * adapter over the model it already has loaded) so the NPU holds one model,
 * and the screens never know which app they are in.
 */
object SkinServices {
    lateinit var dao: SkinDao; private set
    lateinit var photos: PhotoStore; private set
    lateinit var foods: FoodDb; private set
    lateinit var vision: VisionModel; private set
    lateinit var eyes: FaceEyes; private set
    lateinit var appContext: Context; private set
    private var ready = false

    fun init(context: Context, vision: VisionModel? = null) {
        if (ready) return
        appContext = context.applicationContext
        dao = SkinDatabase.get(appContext).dao()
        photos = PhotoStore(appContext)
        foods = FoodDb.bundled()
        this.vision = vision ?: NpuVisionModel(appContext)
        eyes = FaceEyes(appContext)
        ready = true
    }
}

/**
 * Runs one piece of analysis and turns any failure into null, so an odd photo
 * (a circle off the edge, a tiny or corrupt image) shows a message instead of
 * closing the app. The error is logged for us, never shown as a stack trace.
 */
suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
    block()
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (t: Throwable) {
    android.util.Log.w("LittleSigns", "$what failed", t)
    null
}

/** Age helpers for the rules, which all work in days or hours. */
fun BabyEntity.ageDays(now: Long = System.currentTimeMillis()): Int = ((now - birthMs) / 86_400_000L).toInt().coerceAtLeast(0)
fun BabyEntity.ageHours(now: Long = System.currentTimeMillis()): Int = ((now - birthMs) / 3_600_000L).toInt().coerceAtLeast(0)

fun ageText(days: Int): String = when {
    days < 1 -> "Newborn"
    days < 14 -> "$days days old"
    days < 61 -> "${days / 7} weeks old"
    days < 730 -> "${(days / 30.44).toInt()} months old"
    else -> "${days / 365} years old"
}
