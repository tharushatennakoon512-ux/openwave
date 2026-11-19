package com.openwave.core.model

data class SearchResult(
    val track: AudioTrack,
    val query: String,
    /** Source-provided relevance, normalized to 0.0..1.0 */
    val relevance: Double = 0.0
)
