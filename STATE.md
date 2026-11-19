# STATE.md (A1+A2+A3)
Deps: Room+KSP, coroutines, media3-exoplayer/session, Compose BOM 2024.09.03, material-icons-extended, coil-compose
A1/A2: unchanged (see prior snapshot). Packages: com.openwave.{core.model,extractor,cache,algorithm,player,library,downloads}
A3 (new, com.openwave):
- OpenWaveApp: container (lazy), PlaybackRuntime.install(qm); MainActivity (edge-to-edge, notif permission)
- AppContainer: wires library, extractor, streamCache, engine, downloads, playlists, QueueManager; history/pool flows (rec candidates); go(), play(), playAll(), search(), autoRecommend()
- ui: Glass.kt (glass(), springPress(), GlassIcon, MarbleBackground fixed-seed), Orb.kt (OrbDial tap/long-press rotary: Search 180deg, Playlists 225, Downloads 270), Root.kt (Screen enum, PlayerView adapter), Player.kt (now playing+queue), Browse.kt (Search, Playlists, Downloads, TrackRow, DownloadButton, AddToPlaylistButton), PlaylistStore (SharedPreferences JSON)
UNVERIFIED ASSUMPTIONS (fix in AppContainer.kt / Root.kt only):
- AudioExtractorService(ctx), .search(q); RecommendationEngine() no-arg; rankSearchResults(results,history,now); nextRecommendations(seed,cands,history,1,now): List<AudioTrack>
- RecommendationSource = interface of suspend seedTrack()/candidates()/history()
- StreamCacheDatabase.create(ctx).streamCacheDao(); LocalLibraryDatabase.create(ctx).localTrackDao()
- PlaybackUiState: currentTrack, queue, currentIndex, isPlaying, shuffle, repeatMode(enum OFF/ALL/ONE), isLiked, error
- AudioTrack ctor named params (no artistId); downloads.* take trackId String
TODO: persist history, real keystore, launcher icon, A1 extra deps.
