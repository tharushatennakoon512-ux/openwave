package com.openwave.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwave.AppContainer
import com.openwave.player.PlaybackUiState
import com.openwave.core.model.AudioTrack
import kotlinx.coroutines.launch

enum class Screen(val title: String) { Player("Now playing"), Search("Search"), Playlists("Playlists"), Downloads("Downloads") }

/** Compact view of PlaybackUiState. ASSUMPTION: field names below match A2's PlaybackUiState; fix here only. */
data class PlayerView(
    val track: AudioTrack?, val queue: List<AudioTrack>, val index: Int, val playing: Boolean,
    val shuffle: Boolean, val repeat: String, val liked: Boolean, val error: String?,
)
fun PlaybackUiState.toView() = PlayerView(currentTrack, queue, currentIndex, isPlaying, shuffle, repeatMode.name, isLiked, error)

@Composable
fun OpenWaveRoot(c: AppContainer) {
    var screen by rememberSaveable { mutableStateOf(Screen.Player) }
    val pv = c.queueManager.state.collectAsState().value.toView()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler(screen != Screen.Player) { screen = Screen.Player }

    Box(Modifier.fillMaxSize()) {
        MarbleBackground(pv.track?.thumbnailUrl)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screen != Screen.Player) { GlassIcon(Icons.Rounded.ArrowBack, "Back", { screen = Screen.Player }, size = 40.dp); Spacer(Modifier.width(12.dp)) }
                Text(screen.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Marble)
            }
            Box(Modifier.weight(1f)) {
                Crossfade(screen, label = "screen") {
                    when (it) {
                        Screen.Player -> PlayerScreen(c, pv)
                        Screen.Search -> SearchScreen(c)
                        Screen.Playlists -> PlaylistsScreen(c)
                        Screen.Downloads -> DownloadsScreen(c)
                    }
                }
            }
            Spacer(Modifier.height(112.dp)) // fixed room for the orb: layout never shifts
        }
        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        OrbDial(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            playing = pv.playing,
            onTap = {
                if (pv.track != null) c.go { c.queueManager.togglePlayPause() }
                else {
                    val rec = c.autoRecommend()
                    if (rec != null) c.play(rec)
                    else scope.launch { snack.showSnackbar("Nothing to recommend yet. Search for a song or download one first.") }
                }
            },
            onSelect = { screen = it },
        )
    }
}
