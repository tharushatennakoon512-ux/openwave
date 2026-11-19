package com.openwave.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.openwave.algorithm.RecommendationEngine
import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack
import com.openwave.core.model.ListenEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns queue semantics; the ExoPlayer (owned by PlaybackService) is bound via [bindPlayer].
 * Call all methods on the main thread. Commands issued before the service is bound are replayed on bind.
 */
class QueueManager(
    private val sourceResolver: PlaybackSourceResolver,
    private val streamCache: StreamCacheRepository,
    private val recommendationEngine: RecommendationEngine,
    private val recommendationSource: RecommendationSource,
    private val serviceStarter: () -> Unit = {},
    private val autoExtendQueue: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _listenEvents = MutableSharedFlow<ListenEvent>(extraBufferCapacity = 64)
    val listenEvents: SharedFlow<ListenEvent> = _listenEvents.asSharedFlow()

    private val trackIndex = ConcurrentHashMap<String, AudioTrack>()
    val uriResolver = TrackUriResolver(sourceResolver) { trackIndex[it] }

    private val likedIds = HashSet<String>()
    private val refreshAttempts = HashMap<String, Int>()
    private val pending = ArrayList<(Player) -> Unit>()
    private var player: Player? = null
    private var session: ListenSession? = null
    private var currentIsLocal = false
    private var errorMessage: String? = null
    private var lastPrefetchId: String? = null
    private var extending = false
    private var tickerJob: Job? = null

    // ---------------------------------------------------------------- binding

    fun bindPlayer(p: Player) {
        player?.removeListener(listener)
        player = p
        p.addListener(listener)
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                player?.let { _positionMs.value = it.currentPosition.coerceAtLeast(0L) }
                delay(POSITION_TICK_MS)
            }
        }
        val actions = pending.toList()
        pending.clear()
        actions.forEach { it(p) }
        publish()
    }

    fun unbindPlayer() {
        finalizeSession(EndReason.STOPPED)
        player?.removeListener(listener)
        player = null
        tickerJob?.cancel()
        pending.clear()
        trackIndex.clear()
        _positionMs.value = 0L
        _state.value = PlaybackUiState()
    }

    // ---------------------------------------------------------------- commands

    fun playTracks(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        runOnPlayer { p ->
            tracks.forEach { trackIndex[it.id] = it }
            errorMessage = null
            refreshAttempts.clear()
            p.setMediaItems(tracks.map(::toMediaItem), startIndex.coerceIn(tracks.indices), 0L)
            p.prepare()
            p.play()
        }
    }

    fun playTrack(track: AudioTrack) = playTracks(listOf(track))

    /** Empty or finished queue -> auto-play from RecommendationEngine.nextRecommendations. */
    fun play() = runOnPlayer { p ->
        errorMessage = null
        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            autoPlayFromRecommendations(p)
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun pause() = runOnPlayer { it.pause() }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun next() = runOnPlayer { p ->
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        } else {
            val seed = currentTrack(p) ?: return@runOnPlayer
            scope.launch {
                val picks = recommend(seed, queueIds(p))
                if (player === p && picks.isNotEmpty()) {
                    addToQueue(p, picks)
                    p.seekToNextMediaItem()
                }
            }
        }
    }

    fun previous() = runOnPlayer { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = runOnPlayer { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun skipTo(index: Int) = runOnPlayer { p ->
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0L)
            p.play()
        }
    }

    fun enqueue(track: AudioTrack) = runOnPlayer { p ->
        addToQueue(p, listOf(track))
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    fun playNext(track: AudioTrack) = runOnPlayer { p ->
        trackIndex[track.id] = track
        val at = if (p.mediaItemCount == 0) 0 else p.currentMediaItemIndex + 1
        p.addMediaItem(at, toMediaItem(track))
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    fun removeAt(index: Int) = runOnPlayer { p ->
        if (index in 0 until p.mediaItemCount) p.removeMediaItem(index)
    }

    fun clear() = runOnPlayer { p ->
        finalizeSession(EndReason.STOPPED)
        p.stop()
        p.clearMediaItems()
    }

    fun setShuffle(enabled: Boolean) = runOnPlayer { it.shuffleModeEnabled = enabled }

    fun setRepeat(mode: RepeatMode) = runOnPlayer {
        it.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun cycleRepeat() = setRepeat(
        when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    )

    /** Tags subsequent ListenEvents for this track as liked. Persistence is owned elsewhere. */
    fun setLiked(trackId: String, liked: Boolean) {
        if (liked) likedIds.add(trackId) else likedIds.remove(trackId)
    }

    fun dismissError() {
        errorMessage = null
        publish()
    }

    // ---------------------------------------------------------------- player events

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val end = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.COMPLETED
                else -> EndReason.SKIPPED
            }
            finalizeSession(end)
            errorMessage = null
            startSession(mediaItem?.mediaId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) {
                if (session == null) startSession(player?.currentMediaItem?.mediaId)
                session?.resume(now)
                currentTrack()?.let { refreshAttempts.remove(it.id) }
            } else {
                session?.pause(now)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finalizeSession(EndReason.COMPLETED)
        }

        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)

        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) onQueueShapeChanged(p)
            publish()
        }
    }

    private fun onQueueShapeChanged(p: Player) {
        trackIndex.keys.retainAll(queueIds(p))
        val track = currentTrack(p)

        if (track != null) {
            scope.launch {
                val local = sourceResolver.localPath(track) != null
                if (currentTrack()?.id == track.id && currentIsLocal != local) {
                    currentIsLocal = local
                    publish()
                }
            }
        }

        // Pre-fetch the next stream (respects shuffle/repeat via the player's own ordering).
        val nextIdx = p.nextMediaItemIndex
        if (nextIdx != C.INDEX_UNSET) {
            val next = trackIndex[p.getMediaItemAt(nextIdx).mediaId]
            if (next != null && next.id != track?.id && next.id != lastPrefetchId) {
                lastPrefetchId = next.id
                scope.launch { if (sourceResolver.localPath(next) == null) streamCache.prefetchNext(next) }
            }
        }

        // Keep the queue endless when the last track starts.
        if (autoExtendQueue && track != null && !extending && p.mediaItemCount > 0 &&
            p.repeatMode == Player.REPEAT_MODE_OFF && !p.hasNextMediaItem()
        ) {
            extending = true
            scope.launch {
                try {
                    val picks = recommend(track, queueIds(p))
                    if (player === p && picks.isNotEmpty()) addToQueue(p, picks)
                } finally {
                    extending = false
                }
            }
        }
    }

    // ---------------------------------------------------------------- 403 / stream errors

    private fun handlePlayerError(error: PlaybackException) {
        val p = player ?: return
        val track = currentTrack(p)
        if (track == null) {
            setError(error.message ?: "Playback error")
            return
        }
        val attempts = refreshAttempts[track.id] ?: 0
        if (isRecoverable(error) && attempts < MAX_REFRESH_ATTEMPTS) {
            refreshAttempts[track.id] = attempts + 1
            scope.launch {
                val recovered = runCatching {
                    withContext(Dispatchers.IO) {
                        // Local file gone? localPath() prunes the stale row so the resolver falls back to remote.
                        if (sourceResolver.localPath(track) == null) streamCache.refreshedPlaybackUrl(track)
                    }
                }.isSuccess
                if (player !== p) return@launch
                if (recovered) p.prepare() else skipAfterFailure(p, track)
            }
        } else {
            skipAfterFailure(p, track)
        }
    }

    private fun isRecoverable(e: PlaybackException) =
        e.errorCode in 2000..3999 || e.errorCode == PlaybackException.ERROR_CODE_TIMEOUT // IO + parsing (expired/403 pages)

    private fun skipAfterFailure(p: Player, track: AudioTrack) {
        setError("Couldn't play \"${track.title}\"")
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.prepare()
        }
    }

    // ---------------------------------------------------------------- auto-play

    private fun autoPlayFromRecommendations(p: Player) {
        scope.launch {
            val seed = currentTrack(p) ?: withContext(Dispatchers.Default) {
                runCatching { recommendationSource.seedTrack() }.getOrNull()
            }
            if (seed == null) {
                setError("Nothing to play yet")
                return@launch
            }
            val picks = recommend(seed, queueIds(p))
            if (player !== p) return@launch
            if (p.mediaItemCount == 0) {
                playTracks(picks.ifEmpty { listOf(seed) })
            } else if (picks.isEmpty()) {
                p.seekTo(0, 0L) // queue finished, nothing new: replay
                p.play()
            } else {
                val first = p.mediaItemCount
                addToQueue(p, picks)
                p.seekTo(first, 0L)
                p.play()
            }
        }
    }

    private suspend fun recommend(seed: AudioTrack, exclude: Set<String> = emptySet()): List<AudioTrack> =
        withContext(Dispatchers.Default) {
            runCatching {
                val candidates = recommendationSource.candidates(seed)
                val history = recommendationSource.history()
                recommendationEngine.nextRecommendations(seed, candidates, history, RECOMMENDATION_BATCH, clock())
            }.getOrDefault(emptyList())
        }.filter { it.id != seed.id && it.id !in exclude }

    // ---------------------------------------------------------------- listen tracking

    private enum class EndReason { COMPLETED, SKIPPED, STOPPED }

    private class ListenSession(val track: AudioTrack, val startedAtMs: Long, playingSince: Long?) {
        var accumulatedMs = 0L
        var playingSince: Long? = playingSince
        fun resume(now: Long) { if (playingSince == null) playingSince = now }
        fun pause(now: Long) {
            playingSince?.let { accumulatedMs += now - it }
            playingSince = null
        }
        fun listenedMs(now: Long) = accumulatedMs + (playingSince?.let { now - it } ?: 0L)
    }

    private fun startSession(mediaId: String?) {
        val track = mediaId?.let { trackIndex[it] }
        session = track?.let {
            ListenSession(it, clock(), if (player?.isPlaying == true) SystemClock.elapsedRealtime() else null)
        }
    }

    private fun finalizeSession(reason: EndReason) {
        val s = session ?: return
        session = null
        val listened = s.listenedMs(SystemClock.elapsedRealtime())
        if (listened < MIN_EVENT_LISTEN_MS) return
        _listenEvents.tryEmit(
            ListenEvent(
                trackId = s.track.id,
                artistId = s.track.artistId,
                startedAtMs = s.startedAtMs,
                listenedMs = listened,
                trackDurationMs = s.track.durationMs,
                completed = reason == EndReason.COMPLETED,
                skipped = reason == EndReason.SKIPPED,
                liked = s.track.id in likedIds,
            )
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun runOnPlayer(action: (Player) -> Unit) {
        val p = player
        if (p != null) {
            action(p)
        } else {
            pending.add(action)
            serviceStarter()
        }
    }

    private fun addToQueue(p: Player, tracks: List<AudioTrack>) {
        tracks.forEach { trackIndex[it.id] = it }
        p.addMediaItems(tracks.map(::toMediaItem))
    }

    private fun currentTrack(p: Player? = player): AudioTrack? =
        p?.currentMediaItem?.mediaId?.let { trackIndex[it] }

    private fun queueIds(p: Player): Set<String> =
        (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()

    private fun setError(message: String) {
        errorMessage = message
        publish()
    }

    private fun toMediaItem(track: AudioTrack): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setDurationMs(track.durationMs)
        track.thumbnailUrl?.let { meta.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(TrackUriResolver.uriFor(track.id))
            .setMimeType(track.mimeType)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun publish() {
        val p = player ?: return
        val queue = (0 until p.mediaItemCount).mapNotNull { trackIndex[p.getMediaItemAt(it).mediaId] }
        val current = currentTrack(p)
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: current?.durationMs ?: 0L
        _state.value = PlaybackUiState(
            currentTrack = current,
            queue = queue,
            currentIndex = if (current == null) -1 else p.currentMediaItemIndex,
            isPlaying = p.playWhenReady &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING),
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = duration,
            shuffleEnabled = p.shuffleModeEnabled,
            repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            isLocalSource = currentIsLocal,
            error = errorMessage,
        )
        _positionMs.value = p.currentPosition.coerceAtLeast(0L)
    }

    private companion object {
        const val POSITION_TICK_MS = 500L
        const val MIN_EVENT_LISTEN_MS = 1_000L
        const val MAX_REFRESH_ATTEMPTS = 1
        const val RECOMMENDATION_BATCH = 10
    }
}
