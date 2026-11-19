package com.openwave.player

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.ListenEvent

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackUiState(
    val currentTrack: AudioTrack? = null,
    val queue: List<AudioTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isLocalSource: Boolean = false,
    val error: String? = null,
)

/** Supplied by whichever account owns search/history storage. */
interface RecommendationSource {
    /** Track to seed auto-play when nothing has been played yet (e.g. last played / most liked). */
    suspend fun seedTrack(): AudioTrack?
    suspend fun candidates(seed: AudioTrack): List<AudioTrack>
    suspend fun history(): List<ListenEvent>
}
