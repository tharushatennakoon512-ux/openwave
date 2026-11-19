package com.openwave.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "stream_cache")
data class StreamCacheEntity(
    @PrimaryKey val trackId: String,
    val streamUrl: String,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val cachedAtMs: Long,
    val expiresAtMs: Long
)

@Dao
interface StreamCacheDao {
    @Query("SELECT * FROM stream_cache WHERE trackId = :id LIMIT 1")
    suspend fun get(id: String): StreamCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: StreamCacheEntity)

    @Query("DELETE FROM stream_cache WHERE trackId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM stream_cache WHERE expiresAtMs <= :now")
    suspend fun deleteExpired(now: Long): Int
}

@Database(
    entities = [StreamCacheEntity::class, ListenEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class StreamCacheDatabase : RoomDatabase() {
    abstract fun streamCacheDao(): StreamCacheDao
    abstract fun listenHistoryDao(): ListenHistoryDao

    companion object {
        @Volatile
        private var instance: StreamCacheDatabase? = null

        fun create(context: Context): StreamCacheDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StreamCacheDatabase::class.java,
                    "openwave_stream_cache.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
