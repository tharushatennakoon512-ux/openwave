package com.openwave.algorithm

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.ListenEvent
import com.openwave.core.model.SearchResult

class RecommendationEngine(
    private val scorer: TasteScorer = TasteScorer(),
    private val relevanceWeight: Double = 0.65,
    private val tasteWeight: Double = 0.35,
    private val sameArtistBonus: Double = 0.25,
    private val recentWindow: Int = 20,
    private val maxPerArtist: Int = 2
) {
    fun rankSearchResults(
        results: List<SearchResult>,
        history: List<ListenEvent>,
        nowMs: Long = System.currentTimeMillis()
    ): List<SearchResult> {
        val profile = scorer.buildProfile(history, nowMs)
        return results
            .distinctBy { it.track.id }
            .map { r ->
                val s = relevanceWeight * r.relevance.coerceIn(0.0, 1.0) +
                    tasteWeight * scorer.score(r.track, profile)
                r to s
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun nextRecommendations(
        current: AudioTrack?,
        candidates: List<AudioTrack>,
        history: List<ListenEvent>,
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis()
    ): List<AudioTrack> {
        val profile = scorer.buildProfile(history, nowMs)
        val recent = history.sortedByDescending { it.startedAtMs }.take(recentWindow).map { it.trackId }.toSet()
        val excluded = recent + listOfNotNull(current?.id)

        val ranked = candidates
            .distinctBy { it.id }
            .filter { it.id !in excluded }
            .map { t ->
                var s = scorer.score(t, profile)
                if (current != null && t.artistId == current.artistId) s += sameArtistBonus
                t to s
            }
            .sortedByDescending { it.second }

        val perArtist = HashMap<String, Int>()
        val out = ArrayList<AudioTrack>(limit)
        for ((t, _) in ranked) {
            if (out.size >= limit) break
            val n = perArtist.getOrDefault(t.artistId, 0)
            if (n >= maxPerArtist) continue
            perArtist[t.artistId] = n + 1
            out += t
        }
        return out
    }
}
