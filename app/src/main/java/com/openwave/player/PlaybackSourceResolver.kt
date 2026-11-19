@file:OptIn(UnstableApi::class)

package com.openwave.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.openwave.cache.StreamCacheRepository
import com.openwave.core.model.AudioTrack
import com.openwave.library.LocalLibraryRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** Decides per track, at load time: completed local file first, else a (cached/fresh) remote stream. */
class PlaybackSourceResolver(
    private val library: LocalLibraryRepository,
    private val streamCache: StreamCacheRepository,
) {
    suspend fun localPath(track: AudioTrack): String? = library.localFilePath(track.id)

    suspend fun resolveUri(track: AudioTrack): Uri {
        localPath(track)?.let { return Uri.fromFile(File(it)) }
        return Uri.parse(streamCache.playbackUrl(track))
    }
}

/**
 * MediaItems carry a placeholder URI (openwave://track/<id>); the real source is resolved when the
 * loader opens it. This gives seamless local<->remote switching and lets a 403 retry pick up a
 * refreshed URL without rebuilding the playlist.
 */
class TrackUriResolver(
    private val sources: PlaybackSourceResolver,
    private val lookup: (String) -> AudioTrack?,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != SCHEME) return dataSpec
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Missing track id")
        val track = lookup(id) ?: throw IOException("Track $id is no longer queued")
        val resolved = try {
            runBlocking { sources.resolveUri(track) } // runs on ExoPlayer's loader thread
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not resolve stream for $id", e)
        }
        return dataSpec.withUri(resolved)
    }

    companion object {
        const val SCHEME = "openwave"
        fun uriFor(trackId: String): Uri =
            Uri.Builder().scheme(SCHEME).authority("track").appendPath(trackId).build()
    }
}
