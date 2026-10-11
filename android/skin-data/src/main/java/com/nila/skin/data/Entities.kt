package com.nila.skin.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Storage for the skin and early-signs feature.
 *
 * Its own database file (nila_skin.db), so plugging the feature into Nila
 * adds tables without touching Nila's schema or migrations. Photos are files
 * in the app's private storage; rows only hold their paths.
 */

@Entity(tableName = "baby")
data class BabyEntity(
    @PrimaryKey val id: Long = 1,
    val name: String,
    val birthMs: Long,
    val preterm: Boolean = false,
    val demo: Boolean = false,
)

@Entity(tableName = "concern")
data class ConcernEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: String,
    val area: String,
    val createdMs: Long,
    val archived: Boolean = false,
)

@Entity(tableName = "skin_entry", indices = [Index("concernId")])
data class SkinEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val concernId: Long,
    val timeMs: Long,
    val photoPath: String,
    val cx: Double, val cy: Double, val r: Double,
    val coinCx: Double? = null, val coinCy: Double? = null, val coinR: Double? = null, val coinMm: Double? = null,
    val coverage: Double,
    val areaOfPhoto: Double,
    val areaMm2: Double?,
    val spots: Int,
    val deltaA: Double, val deltaL: Double, val deltaB: Double,
    val redness: Double,
    val words: String,
    val aiColour: String? = null,
    val aiSurfaces: String? = null,
    val aiPattern: String? = null,
    val aiRaw: String? = null,
    val answers: String,
    val step: String,
    val reasons: String,
    val note: String = "",
)

@Entity(tableName = "meal")
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val photoPath: String? = null,
    val aiRaw: String? = null,
)

@Entity(tableName = "food_log", indices = [Index("timeMs"), Index("mealId")])
data class FoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodId: String,
    val grams: Double,
    val timeMs: Long,
    val firstTime: Boolean,
    val mealId: Long? = null,
    /** When the parent answered the after-food check-in; null while it is due. */
    val checkedInMs: Long? = null,
)

@Entity(tableName = "reaction", indices = [Index("timeMs")])
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val signs: String,
    val note: String = "",
    val photoPath: String? = null,
    val step: String,
)

@Entity(tableName = "stool_check")
data class StoolCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val photoPath: String?,
    val chosen: String,
    val measured: String?,
    val l: Double?, val a: Double?, val b: Double?,
    val whiteCorrected: Boolean,
    val step: String,
)

@Entity(tableName = "eye_check")
data class EyeCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val photoPath: String,
    val left: String,
    val right: String,
    val step: String,
    val fromGallery: Boolean = false,
)

@Entity(tableName = "yellow_check")
data class YellowCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val ageHours: Int,
    val eyeYellowness: Double?,
    val eyeWhiteCorrected: Boolean,
    val photoPath: String?,
    val answers: String,
    val step: String,
    val reasons: String,
)

@Entity(tableName = "setting")
data class SettingEntity(@PrimaryKey val key: String, val value: String)
