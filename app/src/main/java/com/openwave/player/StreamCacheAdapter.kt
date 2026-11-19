package com.openwave.player

import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack

/** ASSUMPTION: CachedStream exposes `url: String`. Only file that touches CachedStream's shape. */
suspend fun StreamCacheRepository.playbackUrl(track: AudioTrack, forceRefresh: Boolean = false): String =
    getStream(track, forceRefresh = forceRefresh).url

/** 403/stream-error recovery: invalidate(...) then getStream(track, forceRefresh = true). */
suspend fun StreamCacheRepository.refreshedPlaybackUrl(track: AudioTrack): String {
    invalidate(track.id) // ASSUMPTION: invalidate(trackId: String)
    return playbackUrl(track, forceRefresh = true)
}
