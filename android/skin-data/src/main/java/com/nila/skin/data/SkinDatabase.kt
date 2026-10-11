package com.nila.skin.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SkinDao {
    // baby
    @Query("SELECT * FROM baby WHERE id = 1") fun baby(): Flow<BabyEntity?>
    @Query("SELECT * FROM baby WHERE id = 1") suspend fun babyNow(): BabyEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBaby(b: BabyEntity)

    // concerns and skin entries
    @Query("SELECT * FROM concern WHERE archived = 0 ORDER BY createdMs DESC") fun concerns(): Flow<List<ConcernEntity>>
    @Query("SELECT * FROM concern WHERE id = :id") fun concern(id: Long): Flow<ConcernEntity?>
    @Query("SELECT * FROM concern WHERE id = :id") suspend fun concernNow(id: Long): ConcernEntity?
    @Insert suspend fun addConcern(c: ConcernEntity): Long
    @Update suspend fun updateConcern(c: ConcernEntity)
    @Query("SELECT * FROM skin_entry WHERE concernId = :id ORDER BY timeMs") fun entries(id: Long): Flow<List<SkinEntryEntity>>
    @Query("SELECT * FROM skin_entry WHERE concernId = :id ORDER BY timeMs") suspend fun entriesNow(id: Long): List<SkinEntryEntity>
    @Query("SELECT * FROM skin_entry ORDER BY timeMs DESC") fun allEntries(): Flow<List<SkinEntryEntity>>
    @Query("SELECT * FROM skin_entry ORDER BY timeMs DESC") suspend fun allEntriesNow(): List<SkinEntryEntity>
    @Insert suspend fun addEntry(e: SkinEntryEntity): Long
    @Query("SELECT COUNT(*) FROM concern WHERE kind = 'BIRTHMARK' AND archived = 0") suspend fun birthmarkCount(): Int

    // food
    @Insert suspend fun addMeal(m: MealEntity): Long
    @Insert suspend fun addFood(f: FoodLogEntity): Long
    @Update suspend fun updateFood(f: FoodLogEntity)
    @Query("SELECT * FROM food_log ORDER BY timeMs DESC") fun foods(): Flow<List<FoodLogEntity>>
    @Query("SELECT * FROM food_log ORDER BY timeMs DESC") suspend fun foodsNow(): List<FoodLogEntity>
    @Query("SELECT * FROM food_log WHERE timeMs >= :from ORDER BY timeMs") fun foodsSince(from: Long): Flow<List<FoodLogEntity>>
    @Query("DELETE FROM food_log WHERE id = :id") suspend fun deleteFood(id: Long)
    @Query("SELECT * FROM meal ORDER BY timeMs DESC") fun meals(): Flow<List<MealEntity>>

    @Insert suspend fun addReaction(r: ReactionEntity): Long
    @Query("SELECT * FROM reaction ORDER BY timeMs DESC") fun reactions(): Flow<List<ReactionEntity>>
    @Query("SELECT * FROM reaction ORDER BY timeMs DESC") suspend fun reactionsNow(): List<ReactionEntity>

    // checks
    @Insert suspend fun addStool(s: StoolCheckEntity): Long
    @Query("SELECT * FROM stool_check ORDER BY timeMs DESC") fun stools(): Flow<List<StoolCheckEntity>>
    @Query("SELECT * FROM stool_check ORDER BY timeMs DESC") suspend fun stoolsNow(): List<StoolCheckEntity>
    @Insert suspend fun addEye(e: EyeCheckEntity): Long
    @Query("SELECT * FROM eye_check ORDER BY timeMs DESC") fun eyes(): Flow<List<EyeCheckEntity>>
    @Query("SELECT * FROM eye_check ORDER BY timeMs DESC") suspend fun eyesNow(): List<EyeCheckEntity>
    @Insert suspend fun addYellow(y: YellowCheckEntity): Long
    @Query("SELECT * FROM yellow_check ORDER BY timeMs DESC") fun yellows(): Flow<List<YellowCheckEntity>>
    @Query("SELECT * FROM yellow_check ORDER BY timeMs DESC") suspend fun yellowsNow(): List<YellowCheckEntity>

    // settings
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(s: SettingEntity)
    @Query("SELECT value FROM setting WHERE `key` = :key") suspend fun setting(key: String): String?
    @Query("SELECT value FROM setting WHERE `key` = :key") fun settingFlow(key: String): Flow<String?>

    // wipe
    @Query("DELETE FROM concern") suspend fun wipeConcerns()
    @Query("DELETE FROM skin_entry") suspend fun wipeEntries()
    @Query("DELETE FROM meal") suspend fun wipeMeals()
    @Query("DELETE FROM food_log") suspend fun wipeFoods()
    @Query("DELETE FROM reaction") suspend fun wipeReactions()
    @Query("DELETE FROM stool_check") suspend fun wipeStools()
    @Query("DELETE FROM eye_check") suspend fun wipeEyes()
    @Query("DELETE FROM yellow_check") suspend fun wipeYellows()
    @Query("DELETE FROM baby") suspend fun wipeBaby()
}

@Database(
    entities = [BabyEntity::class, ConcernEntity::class, SkinEntryEntity::class, MealEntity::class, FoodLogEntity::class,
        ReactionEntity::class, StoolCheckEntity::class, EyeCheckEntity::class, YellowCheckEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SkinDatabase : RoomDatabase() {
    abstract fun dao(): SkinDao

    companion object {
        @Volatile private var instance: SkinDatabase? = null
        fun get(context: Context): SkinDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SkinDatabase::class.java, "nila_skin.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
