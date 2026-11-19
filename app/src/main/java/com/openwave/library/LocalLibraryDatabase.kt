package com.openwave.library

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_tracks")
data class LocalTrackEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val sourceType: String,
    val sourceUrl: String,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val filePath: String,
    val sizeBytes: Long,
    val downloadedAtMs: Long,
)

@Dao
interface LocalTrackDao {
    @Query("SELECT * FROM local_tracks ORDER BY downloadedAtMs DESC")
    fun observeAll(): Flow<List<LocalTrackEntity>>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM local_tracks")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM local_tracks")
    suspend fun totalBytes(): Long

    @Query("SELECT * FROM local_tracks")
    suspend fun getAll(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks WHERE trackId = :trackId")
    suspend fun get(trackId: String): LocalTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalTrackEntity)

    @Query("DELETE FROM local_tracks WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}

@Database(entities = [LocalTrackEntity::class], version = 1, exportSchema = false)
abstract class LocalLibraryDatabase : RoomDatabase() {
    abstract fun localTrackDao(): LocalTrackDao

    companion object {
        fun create(context: Context): LocalLibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LocalLibraryDatabase::class.java, "openwave_library.db")
                .build()
    }
}
