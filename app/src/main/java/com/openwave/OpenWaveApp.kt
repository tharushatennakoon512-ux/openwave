package com.openwave

import android.app.Application
import com.openwave.player.PlaybackRuntime

class OpenWaveApp : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        PlaybackRuntime.install(container.queueManager)
    }
}
