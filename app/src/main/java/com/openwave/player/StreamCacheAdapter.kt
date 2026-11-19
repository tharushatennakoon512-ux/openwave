package com.openwave.player

import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack

suspend fun StreamCacheRepository.playbackUrl(track: AudioTrack, forceRefresh: Boolean = false): String =
    getStream(track, forceRefresh = forceRefresh).streamUrl

suspend fun StreamCacheRepository.refreshedPlaybackUrl(track: AudioTrack): String {
    invalidate(track.id)
    return playbackUrl(track, forceRefresh = true)
}
