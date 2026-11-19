package com.openwave.ui

import android.content.Context
import com.openwave.core.model.AudioTrack
import com.openwave.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Tiny SharedPreferences-backed playlists (no A1/A2 equivalent existed). */
class PlaylistStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<String, List<AudioTrack>>> = _state

    fun create(name: String) = mutate { m -> if (name in m) m else m + (name to emptyList<AudioTrack>()) }
    fun add(name: String, t: AudioTrack) = mutate { m -> m + (name to ((m[name].orEmpty()).filter { it.id != t.id } + t)) }
    fun remove(name: String, id: String) = mutate { m -> m + (name to m[name].orEmpty().filter { it.id != id }) }
    fun delete(name: String) = mutate { it - name }

    private fun mutate(f: (Map<String, List<AudioTrack>>) -> Map<String, List<AudioTrack>>) {
        _state.update(f); prefs.edit().putString("data", encode(_state.value)).apply()
    }

    private fun encode(m: Map<String, List<AudioTrack>>) = JSONObject().apply {
        m.forEach { (n, l) ->
            put(n, JSONArray().apply {
                l.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id); put("title", t.title); put("artist", t.artist)
                        put("album", t.album ?: JSONObject.NULL); put("dur", t.durationMs)
                        put("thumb", t.thumbnailUrl ?: JSONObject.NULL)
                        put("src", t.sourceType.name); put("url", t.sourceUrl)
                    })
                }
            })
        }
    }.toString()

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

    // ASSUMPTION: AudioTrack ctor takes these named params; artistId is derived.
    private fun load(): Map<String, List<AudioTrack>> = runCatching {
        val o = JSONObject(prefs.getString("data", "{}") ?: "{}")
        o.keys().asSequence().associateWith { n ->
            val a = o.getJSONArray(n)
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                AudioTrack(
                    id = j.getString("id"), title = j.getString("title"), artist = j.getString("artist"),
                    album = j.str("album"), durationMs = j.getLong("dur"), thumbnailUrl = j.str("thumb"),
                    sourceType = SourceType.valueOf(j.getString("src")), sourceUrl = j.getString("url"),
                    streamUrl = null, streamExpiresAtMs = null, mimeType = null, bitrateKbps = null,
                )
            }
        }
    }.getOrDefault(emptyMap())
}
