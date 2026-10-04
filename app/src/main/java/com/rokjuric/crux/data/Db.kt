package com.rokjuric.crux.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Small JSON documents: the current plan and the intervals.icu caches. */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** One workout log. [json] is the full [WorkoutLog]; the columns are for querying. */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey val id: String,
    val date: String,
    val sessionId: String?,
    val status: String,
    val json: String,
    val synced: Boolean,
    val updatedAt: Long,
)

@Dao
interface KvDao {
    @Query("SELECT * FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<KvEntity?>

    @Query("SELECT * FROM kv WHERE `key` = :key")
    suspend fun get(key: String): KvEntity?

    @Upsert
    suspend fun put(entity: KvEntity)

    @Query("DELETE FROM kv WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY date DESC, updatedAt DESC")
    fun observeAll(): Flow<List<LogEntity>>

    @Query("SELECT * FROM logs WHERE id = :id")
    suspend fun get(id: String): LogEntity?

    @Query("SELECT * FROM logs WHERE status = 'in_progress' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun active(): LogEntity?

    @Query("SELECT * FROM logs WHERE synced = 0 AND status != 'in_progress'")
    suspend fun unsynced(): List<LogEntity>

    @Query("SELECT COUNT(*) FROM logs WHERE synced = 0 AND status != 'in_progress'")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT id FROM logs")
    suspend fun allIds(): List<String>

    @Upsert
    suspend fun put(entity: LogEntity)

    /** Only marks it synced if nobody edited it while the upload was in flight. */
    @Query("UPDATE logs SET synced = 1 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [KvEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class CruxDb : RoomDatabase() {
    abstract fun kv(): KvDao
    abstract fun logs(): LogDao

    companion object {
        fun open(context: Context): CruxDb =
            Room.databaseBuilder(context, CruxDb::class.java, "crux.db").build()
    }
}
