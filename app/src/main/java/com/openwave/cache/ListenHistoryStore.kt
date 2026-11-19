package com.openwave.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.openwave.core.model.ListenEvent
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "listen_history")
data class ListenEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: String,
    val artistId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val liked: Boolean
) {
    fun toModel() = ListenEvent(
        trackId = trackId,
        artistId = artistId,
        startedAtMs = startedAtMs,
        listenedMs = listenedMs,
        trackDurationMs = trackDurationMs,
        completed = completed,
        skipped = skipped,
        liked = liked
    )

    companion object {
        fun fromModel(e: ListenEvent) = ListenEventEntity(
            trackId = e.trackId,
            artistId = e.artistId,
            startedAtMs = e.startedAtMs,
            listenedMs = e.listenedMs,
            trackDurationMs = e.trackDurationMs,
            completed = e.completed,
            skipped = e.skipped,
            liked = e.liked
        )
    }
}

@Dao
interface ListenHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ListenEventEntity)

    @Query("SELECT * FROM listen_history ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 500): List<ListenEventEntity>

    @Query("SELECT * FROM listen_history ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<ListenEventEntity>>
}
