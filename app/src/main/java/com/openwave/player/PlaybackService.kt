@file:OptIn(UnstableApi::class)

package com.openwave.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Install once from your DI/Application before any playback call. */
object PlaybackRuntime {
    @Volatile
    var queueManager: QueueManager? = null
        private set

    fun install(queueManager: QueueManager) {
        this.queueManager = queueManager
    }
}

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var queueManager: QueueManager? = null

    override fun onCreate() {
        super.onCreate()
        val qm = PlaybackRuntime.queueManager
        if (qm == null) {
            stopSelf()
            return
        }

        val dataSourceFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this), qm.uriResolver)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val builder = MediaSession.Builder(this, exo)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        mediaSession = builder.build()
        queueManager = qm
        qm.bindPlayer(exo)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        queueManager?.unbindPlayer()
        queueManager = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
