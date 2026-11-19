package com.openwave.core.model

data class ListenEvent(
    val trackId: String,
    val artistId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val liked: Boolean = false
) {
    val listenRatio: Double
        get() = if (trackDurationMs > 0) (listenedMs.toDouble() / trackDurationMs).coerceIn(0.0, 1.0) else 0.0
}
