package com.openwave.extractor

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.SearchResult
import java.io.File

data class AudioStream(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int?,
    val isAudioOnly: Boolean,
    val expiresAtMs: Long? = null,
    val headers: Map<String, String> = emptyMap()
)

data class ExtractionResult(val track: AudioTrack, val streams: List<AudioStream>) {
    /** Prefers audio-only streams, then highest bitrate. */
    fun bestStream(): AudioStream? =
        streams.sortedWith(
            compareByDescending<AudioStream> { it.isAudioOnly }.thenByDescending { it.bitrateKbps ?: 0 }
        ).firstOrNull()
}

/** Pluggable extractor. Throw [ExtractionError] subtypes or IOException; the service normalizes. */
interface StreamExtractor {
    val name: String
    fun supports(sourceUrl: String): Boolean
    suspend fun extract(sourceUrl: String): ExtractionResult
    suspend fun search(query: String, limit: Int = 20): List<SearchResult> = emptyList()
}

/** Optional local conversion/tagging (e.g., FFmpeg-backed). */
interface AudioPostProcessor {
    suspend fun convertAndTag(input: File, output: File, track: AudioTrack, targetMimeType: String = "audio/mp4"): File
}
