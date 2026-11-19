package com.openwave.algorithm

import com.openwave.core.model.AudioTrack
import com.openwave.core.model.ListenEvent
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

data class TasteProfile(
    val trackAffinity: Map<String, Double>,
    val artistAffinity: Map<String, Double>
)

class TasteScorer(
    private val halfLifeDays: Double = 21.0,
    private val earlySkipRatio: Double = 0.25,
    private val earlySkipMs: Long = 30_000L,
    private val earlySkipPenalty: Double = 1.5,
    private val trackWeight: Double = 0.6,
    private val artistWeight: Double = 0.4
) {
    private val msPerDay = 86_400_000.0

    fun eventValue(e: ListenEvent): Double {
        val earlySkip = e.skipped && (e.listenRatio < earlySkipRatio || e.listenedMs < earlySkipMs)
        var v = when {
            earlySkip -> -earlySkipPenalty
            e.completed -> 1.0
            e.skipped -> e.listenRatio - 0.5
            else -> e.listenRatio
        }
        if (e.liked) v += 1.0
        return v
    }

    fun decay(eventTimeMs: Long, nowMs: Long): Double {
        val ageDays = ((nowMs - eventTimeMs).coerceAtLeast(0)) / msPerDay
        return exp(-ln(2.0) * ageDays / halfLifeDays)
    }

    fun buildProfile(events: List<ListenEvent>, nowMs: Long): TasteProfile {
        val tracks = HashMap<String, Double>()
        val artists = HashMap<String, Double>()
        for (e in events) {
            val w = eventValue(e) * decay(e.startedAtMs, nowMs)
            tracks.merge(e.trackId, w, Double::plus)
            artists.merge(e.artistId, w, Double::plus)
        }
        return TasteProfile(tracks.mapValues { tanh(it.value) }, artists.mapValues { tanh(it.value) })
    }

    /** Returns affinity in -1.0..1.0 (0 = unknown). */
    fun score(track: AudioTrack, profile: TasteProfile): Double =
        trackWeight * (profile.trackAffinity[track.id] ?: 0.0) +
            artistWeight * (profile.artistAffinity[track.artistId] ?: 0.0)
}
