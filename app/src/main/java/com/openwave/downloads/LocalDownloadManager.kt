package com.openwave.downloads

import android.os.SystemClock
import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack
import com.openwave.core.model.DownloadState
import com.openwave.library.LocalLibraryRepository
import com.openwave.player.playbackUrl
import com.openwave.player.refreshedPlaybackUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class LocalDownloadManager(
    private val downloadDir: File,
    private val library: LocalLibraryRepository,
    private val streamCache: StreamCacheRepository,
    maxConcurrent: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val tracks = ConcurrentHashMap<String, AudioTrack>()
    private val gate = Semaphore(maxConcurrent)

    /** Single source of truth: completed rows come from the library, everything else from live state. */
    val states: StateFlow<Map<String, DownloadState>> =
        combine(active, library.observeCompleted()) { live, done -> live + done }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun observe(trackId: String): Flow<DownloadState> =
        states.map { it[trackId] ?: DownloadState.Idle }.distinctUntilChanged()

    fun stateOf(trackId: String): DownloadState = states.value[trackId] ?: DownloadState.Idle

    // ---------------------------------------------------------------- controls

    fun enqueue(track: AudioTrack) = start(track)

    /** Works for Failed and Paused tracks (Failed restarts from scratch; Paused resumes via HTTP Range). */
    fun retry(trackId: String) {
        tracks[trackId]?.let(::start)
    }

    fun resume(trackId: String) = retry(trackId)

    @Synchronized
    fun pause(trackId: String) {
        val s = active.value[trackId]
        if (s is DownloadState.Queued || s is DownloadState.Downloading) {
            jobs.remove(trackId)?.cancel()
            update(trackId, DownloadState.Paused) // partial file kept for resume
        }
    }

    @Synchronized
    fun cancel(trackId: String) {
        jobs.remove(trackId)?.cancel()
        tracks.remove(trackId)
        active.update { it - trackId }
        partFile(trackId).delete()
    }

    /** Cancels any in-flight download and removes the completed local file + index row. */
    suspend fun delete(trackId: String): Boolean {
        cancel(trackId)
        return library.delete(trackId)
    }

    /** Call once at startup: process death loses Paused state, so orphaned .part files are removed. */
    fun cleanupStalePartials() {
        val keep = active.value.filterValues { it is DownloadState.Paused || it is DownloadState.Downloading }
            .keys.map { partFile(it).name }.toSet()
        downloadDir.listFiles { f -> f.name.endsWith(PART_SUFFIX) }
            ?.filter { it.name !in keep }
            ?.forEach { it.delete() }
    }

    fun close() = scope.cancel()

    // ---------------------------------------------------------------- engine

    @Synchronized
    private fun start(track: AudioTrack) {
        if (jobs[track.id]?.isActive == true) return
        tracks[track.id] = track
        update(track.id, DownloadState.Queued)
        jobs[track.id] = scope.launch { gate.withPermit { run(track) } }
    }

    private suspend fun run(track: AudioTrack) {
        val id = track.id
        val part = partFile(id)
        try {
            if (library.isDownloaded(id)) {
                active.update { it - id }
                return
            }
            downloadDir.mkdirs()
            val file = downloadWithRefresh(track, part)
            library.register(track, file.absolutePath, file.length())
            active.update { it - id } // library now reports Completed
        } catch (e: CancellationException) {
            throw e // pause()/cancel() own partial-file handling
        } catch (e: Throwable) {
            if (!currentCoroutineContext().isActive) return
            part.delete() // partial-file cleanup on failure
            update(id, failureState(e))
        }
    }

    private suspend fun downloadWithRefresh(track: AudioTrack, part: File): File {
        var refreshed = false
        var url = streamCache.playbackUrl(track)
        while (true) {
            try {
                return transfer(track, url, part)
            } catch (e: HttpStatusException) {
                if (!refreshed && e.code in EXPIRED_CODES) {
                    refreshed = true
                    url = streamCache.refreshedPlaybackUrl(track) // invalidate + getStream(forceRefresh = true)
                } else {
                    throw DownloadException("HTTP ${e.code}", retryable = e.code >= 500 || e.code in EXPIRED_CODES || e.code == 429)
                }
            }
        }
    }

    private suspend fun transfer(track: AudioTrack, url: String, part: File): File {
        val id = track.id
        var resumeFrom = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity") // keep Content-Length honest
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { // stale/oversized partial: restart clean
                part.delete()
                return transfer(track, url, part)
            }
            if (code !in 200..299) throw HttpStatusException(code)

            val append = code == 206 && resumeFrom > 0
            if (!append) resumeFrom = 0L // server ignored Range
            val remaining = conn.contentLengthLong.takeIf { it >= 0 }
            val total = remaining?.let { resumeFrom + it }
            if (remaining != null && downloadDir.usableSpace < remaining + MIN_FREE_BYTES) {
                throw DownloadException("Not enough storage space", retryable = false)
            }

            var downloaded = resumeFrom
            var lastEmitAt = 0L
            emitProgress(id, downloaded, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                            lastEmitAt = now
                            emitProgress(id, downloaded, total)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (total != null && downloaded != total) {
                throw IOException("Incomplete download ($downloaded of $total bytes)")
            }
            emitProgress(id, downloaded, total ?: downloaded)

            val target = File(downloadDir, baseName(id) + "." + extensionFor(track.mimeType ?: conn.contentType))
            target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalize download")
            return target
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun emitProgress(id: String, downloaded: Long, total: Long?) {
        val fraction = if (total != null && total > 0) {
            (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
        } else 0f
        update(id, DownloadState.Downloading(progress = fraction, bytesDownloaded = downloaded, totalBytes = total))
    }

    private fun update(id: String, state: DownloadState) = active.update { it + (id to state) }

    private fun failureState(e: Throwable): DownloadState.Failed = when (e) {
        is DownloadException -> DownloadState.Failed(reason = e.message ?: "Download failed", retryable = e.retryable)
        else -> DownloadState.Failed(
            reason = e.message ?: "Download failed",
            retryable = !(e is IOException && e.message?.contains("No space left", ignoreCase = true) == true),
        )
    }

    private fun baseName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64) + "-" + id.hashCode().toUInt().toString(16)

    private fun partFile(id: String) = File(downloadDir, baseName(id) + PART_SUFFIX)

    private fun extensionFor(mime: String?): String = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/aac" -> "m4a"
        "audio/webm" -> "webm"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "audio"
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")
    private class DownloadException(message: String, val retryable: Boolean) : Exception(message)

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 32 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val PROGRESS_INTERVAL_MS = 200L
        const val MIN_FREE_BYTES = 10L * 1024 * 1024
        val EXPIRED_CODES = setOf(401, 403, 410)
    }
}
