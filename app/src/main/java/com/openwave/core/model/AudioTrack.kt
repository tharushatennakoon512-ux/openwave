package com.openwave.core.model

enum class SourceType { YOUTUBE, SOUNDCLOUD, DIRECT, LOCAL, OTHER }

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
    val sourceType: SourceType = SourceType.OTHER,
    val sourceUrl: String,
    val streamUrl: String? = null,
    val streamExpiresAtMs: Long? = null,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null
) {
    val artistId: String get() = artist.trim().lowercase()
}
