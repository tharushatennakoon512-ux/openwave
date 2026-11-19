package com.openwave.extractor

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AudioExtractorService(
    private val extractors: List<StreamExtractor>,
    private val postProcessor: AudioPostProcessor? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun extract(sourceUrl: String): ExtractionResult = withContext(io) {
        val extractor = extractors.firstOrNull { it.supports(sourceUrl) }
            ?: throw UnsupportedSourceError(sourceUrl)
        val result = guard { extractor.extract(sourceUrl) }
        if (result.bestStream() == null) throw ExtractionFailedError("No playable audio streams for $sourceUrl")
        result
    }

    /** Returns the track with streamUrl/expiry/mime/bitrate filled from the best stream. */
    suspend fun resolve(track: AudioTrack): AudioTrack {
        val result = extract(track.sourceUrl)
        val s = result.bestStream()!!
        return track.copy(
            streamUrl = s.url,
            streamExpiresAtMs = s.expiresAtMs,
            mimeType = s.mimeType,
            bitrateKbps = s.bitrateKbps
        )
    }

    suspend fun resolveCatching(track: AudioTrack): Result<AudioTrack> =
        try { Result.success(resolve(track)) } catch (e: ExtractionError) { Result.failure(e) }

    suspend fun search(query: String, limit: Int = 20): List<SearchResult> = coroutineScope {
        extractors.map { ex ->
            async(io) { runCatching { ex.search(query, limit) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun convertAndTag(input: File, output: File, track: AudioTrack, mime: String = "audio/mp4"): File =
        withContext(io) {
            val p = postProcessor ?: throw UnsupportedSourceError("No post-processor configured")
            guard { p.convertAndTag(input, output, track, mime) }
        }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ExtractionError) {
        throw e
    } catch (e: IOException) {
        throw NetworkError(e.message ?: "Network error", e)
    } catch (e: Exception) {
        throw ExtractionFailedError(e.message ?: "Extraction failed", e)
    }
}
