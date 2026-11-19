package com.openwave.cache

import com.openwave.core.model.AudioTrack
import com.openwave.extractor.AudioExtractorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class CachedStream(
    val trackId: String,
    val streamUrl: String,
    val mimeType: String?,
    val bitrateKbps: Int?,
    val expiresAtMs: Long
)

class StreamCacheRepository(
    private val dao: StreamCacheDao,
    private val extractor: AudioExtractorService,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val safetyMarginMs: Long = 60_000L,
    private val defaultTtlMs: Long = 5L * 60 * 60 * 1000
) {
    private val memory = ConcurrentHashMap<String, CachedStream>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val prefetchJobs = ConcurrentHashMap<String, Job>()

    fun isValid(s: CachedStream): Boolean = s.expiresAtMs - safetyMarginMs > clock()

    /** Memory -> Room -> extractor. Concurrent calls for the same track are de-duplicated. */
    suspend fun getStream(track: AudioTrack, forceRefresh: Boolean = false): CachedStream {
        if (!forceRefresh) peekMemory(track.id)?.let { return it }
        val lock = locks.getOrPut(track.id) { Mutex() }
        return lock.withLock {
            if (!forceRefresh) {
                peekMemory(track.id)?.let { return@withLock it }
                dao.get(track.id)?.toModel()?.takeIf(::isValid)?.let {
                    memory[track.id] = it
                    return@withLock it
                }
            }
            refresh(track)
        }
    }

    fun peekMemory(trackId: String): CachedStream? =
        memory[trackId]?.takeIf(::isValid) ?: run { memory.remove(trackId); null }

    /** Call when the player reports an expired/403 stream. */
    suspend fun invalidate(trackId: String) {
        memory.remove(trackId)
        dao.delete(trackId)
    }

    /** Fire-and-forget pre-fetch for the upcoming queue item. */
    fun prefetchNext(next: AudioTrack?): Job? {
        if (next == null || peekMemory(next.id) != null) return null
        prefetchJobs[next.id]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            runCatching { getStream(next) }
        }
        job.invokeOnCompletion { prefetchJobs.remove(next.id) }
        prefetchJobs[next.id] = job
        return job
    }

    suspend fun purgeExpired(): Int {
        val now = clock()
        memory.entries.removeIf { it.value.expiresAtMs <= now }
        return dao.deleteExpired(now)
    }

    suspend fun clearAll() {
        memory.clear()
        dao.deleteExpired(Long.MAX_VALUE)
    }

    private suspend fun refresh(track: AudioTrack): CachedStream {
        val resolved = extractor.resolve(track)
        val now = clock()
        val cached = CachedStream(
            trackId = track.id,
            streamUrl = resolved.streamUrl!!,
            mimeType = resolved.mimeType,
            bitrateKbps = resolved.bitrateKbps,
            expiresAtMs = resolved.streamExpiresAtMs ?: (now + defaultTtlMs)
        )
        memory[track.id] = cached
        dao.put(
            StreamCacheEntity(cached.trackId, cached.streamUrl, cached.mimeType, cached.bitrateKbps, now, cached.expiresAtMs)
        )
        return cached
    }

    private fun StreamCacheEntity.toModel() =
        CachedStream(trackId, streamUrl, mimeType, bitrateKbps, expiresAtMs)
}
