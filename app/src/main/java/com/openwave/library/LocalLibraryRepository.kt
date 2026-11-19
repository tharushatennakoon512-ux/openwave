package com.openwave.library

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.DownloadState
import com.openwave.core.model.SourceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class LocalLibraryRepository(
    private val dao: LocalTrackDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Offline tracks, newest first. */
    val tracks: Flow<List<AudioTrack>> = dao.observeAll().map { rows -> rows.map { it.toAudioTrack() } }

    /** Total bytes used by completed local downloads. */
    val totalStorageBytes: Flow<Long> = dao.observeTotalBytes().distinctUntilChanged()

    /** Completed downloads keyed by track id, for merging into DownloadState maps. */
    fun observeCompleted(): Flow<Map<String, DownloadState.Completed>> =
        dao.observeAll().map { rows ->
            rows.associate { it.trackId to DownloadState.Completed(filePath = it.filePath, sizeBytes = it.sizeBytes) }
        }

    suspend fun register(track: AudioTrack, filePath: String, sizeBytes: Long) = withContext(io) {
        dao.upsert(
            LocalTrackEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                thumbnailUrl = track.thumbnailUrl,
                sourceType = track.sourceType.name,
                sourceUrl = track.sourceUrl,
                mimeType = track.mimeType,
                bitrateKbps = track.bitrateKbps,
                filePath = filePath,
                sizeBytes = sizeBytes,
                downloadedAtMs = clock(),
            )
        )
    }

    /** Path of the completed local file, or null. Prunes the row if the file has vanished. */
    suspend fun localFilePath(trackId: String): String? = withContext(io) {
        val row = dao.get(trackId) ?: return@withContext null
        if (File(row.filePath).isFile) {
            row.filePath
        } else {
            dao.delete(trackId)
            null
        }
    }

    suspend fun isDownloaded(trackId: String): Boolean = localFilePath(trackId) != null

    suspend fun getTrack(trackId: String): AudioTrack? = withContext(io) { dao.get(trackId)?.toAudioTrack() }

    suspend fun totalStorageUsed(): Long = withContext(io) { dao.totalBytes() }

    /** Deletes the file, then the index row. Returns false if the file could not be removed. */
    suspend fun delete(trackId: String): Boolean = withContext(io) {
        val row = dao.get(trackId) ?: return@withContext false
        val file = File(row.filePath)
        if (file.exists() && !file.delete()) return@withContext false
        dao.delete(trackId)
        true
    }

    /** Returns the number of tracks removed. */
    suspend fun deleteAll(): Int = withContext(io) {
        var removed = 0
        dao.getAll().forEach { row ->
            val file = File(row.filePath)
            if (!file.exists() || file.delete()) {
                dao.delete(row.trackId)
                removed++
            }
        }
        removed
    }

    /** Drops index rows whose files no longer exist (e.g. user cleared app storage). */
    suspend fun reconcile(): Int = withContext(io) {
        var removed = 0
        dao.getAll().forEach { row ->
            if (!File(row.filePath).isFile) {
                dao.delete(row.trackId)
                removed++
            }
        }
        removed
    }

    private fun LocalTrackEntity.toAudioTrack() = AudioTrack(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrElse { SourceType.values().first() },
        sourceUrl = sourceUrl,
        streamUrl = null,
        streamExpiresAtMs = null,
        mimeType = mimeType,
        bitrateKbps = bitrateKbps,
    )
}
