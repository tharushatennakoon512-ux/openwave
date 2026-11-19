package com.openwave

import android.app.Application
import com.openwave.algorithm.RecommendationEngine
import com.openwave.cache.ListenEventEntity
import com.openwave.cache.StreamCacheDatabase
import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack
import com.openwave.core.model.ListenEvent
import com.openwave.core.model.SearchResult
import com.openwave.downloads.LocalDownloadManager
import com.openwave.extractor.AudioExtractorService
import com.openwave.library.LocalLibraryDatabase
import com.openwave.library.LocalLibraryRepository
import com.openwave.player.*
import com.openwave.ui.PlaylistStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class AppContainer(app: Application) {
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = { System.currentTimeMillis() }

    private val streamDb = StreamCacheDatabase.create(app)
    private val listenHistoryDao = streamDb.listenHistoryDao()

    val library = LocalLibraryRepository(LocalLibraryDatabase.create(app).localTrackDao())
    // No StreamExtractor implementations exist yet; add them to this list.
    val extractor = AudioExtractorService(emptyList())
    val streamCache = StreamCacheRepository(
        dao = streamDb.streamCacheDao(),
        extractor = extractor, scope = bgScope, clock = clock,
        safetyMarginMs = 60_000L, defaultTtlMs = 3_600_000L,
    )
    val engine = RecommendationEngine()
    val downloads = LocalDownloadManager(
        downloadDir = File(app.filesDir, "downloads").apply { mkdirs() },
        library = library, streamCache = streamCache,
    )
    val playlists = PlaylistStore(app)

    val history = MutableStateFlow<List<ListenEvent>>(emptyList())
    val pool = MutableStateFlow<Map<String, AudioTrack>>(emptyMap())
    val likedIds = MutableStateFlow<Set<String>>(emptySet())
    private val lastTrack = MutableStateFlow<AudioTrack?>(null)

    private val recSource = object : RecommendationSource {
        override suspend fun seedTrack(): AudioTrack? = lastTrack.value
        override suspend fun candidates(seed: AudioTrack): List<AudioTrack> =
            pool.value.values.filter { it.id != seed.id }
        override suspend fun history(): List<ListenEvent> = this@AppContainer.history.value
    }

    val queueManager = QueueManager(
        sourceResolver = PlaybackSourceResolver(library, streamCache),
        streamCache = streamCache,
        recommendationEngine = engine,
        recommendationSource = recSource,
        serviceStarter = { PlaybackService.start(app) },
    )

    init {
        uiScope.launch(Dispatchers.IO) {
            val saved = listenHistoryDao.getRecent(500).map { it.toModel() }.reversed()
            history.value = saved
        }
        uiScope.launch { queueManager.state.collect { s -> s.currentTrack?.let { remember(listOf(it)); lastTrack.value = it } } }
        uiScope.launch {
            queueManager.listenEvents.collect { e ->
                history.update { (it + e).takeLast(500) }
                bgScope.launch(Dispatchers.IO) {
                    listenHistoryDao.insert(ListenEventEntity.fromModel(e))
                }
            }
        }
        uiScope.launch { library.tracks.collect { remember(it) } }
        uiScope.launch { playlists.state.collect { m -> remember(m.values.flatten()) } }
    }

    fun remember(tracks: Collection<AudioTrack>) = pool.update { it + tracks.associateBy { t -> t.id } }
    fun go(block: suspend () -> Unit) { uiScope.launch { block() } }
    fun play(t: AudioTrack) = go { queueManager.playTrack(t) }
    fun playAll(l: List<AudioTrack>) = go { if (l.isNotEmpty()) { queueManager.playTrack(l.first()); l.drop(1).forEach { queueManager.enqueue(it) } } }

    fun toggleLike(t: AudioTrack) {
        val nowLiked = t.id !in likedIds.value
        likedIds.update { if (nowLiked) it + t.id else it - t.id }
        queueManager.setLiked(t.id, nowLiked)
    }

    suspend fun search(q: String): List<SearchResult> {
        val raw = extractor.search(q)
        val ranked = engine.rankSearchResults(raw, history.value, clock())
        remember(ranked.map { it.track })
        return ranked
    }

    fun autoRecommend(): AudioTrack? {
        val cands = pool.value.values.toList()
        if (cands.isEmpty()) return null
        val seed = lastTrack.value ?: cands.first()
        return engine.nextRecommendations(seed, cands, history.value, 1, clock()).firstOrNull() ?: cands.firstOrNull { it.id != seed.id } ?: seed
    }
}
