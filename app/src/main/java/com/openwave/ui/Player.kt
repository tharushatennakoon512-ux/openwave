package com.openwave.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openwave.AppContainer

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun PlayerScreen(c: AppContainer, pv: PlayerView) {
    val pos by c.queueManager.positionMs.collectAsState()
    val t = pv.track
    val dur = (t?.durationMs ?: 0L).coerceAtLeast(1L)
    var drag by remember { mutableStateOf<Float?>(null) }
    val q = c.queueManager

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(.55f).aspectRatio(1f).glass(RoundedCornerShape(28.dp), 1.2f), Alignment.Center) {
            if (t?.thumbnailUrl != null) AsyncImage(t.thumbnailUrl, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, tint = Marble.copy(.6f), modifier = Modifier.size(56.dp))
        }
        Text(t?.title ?: "Nothing playing", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Marble, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t?.artist ?: "Tap the orb to play something you might like", fontSize = 14.sp, color = Marble.copy(.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f), enabled = t != null,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { v -> c.go { q.seekTo((v * dur).toLong()) } }; drag = null },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(pos), fontSize = 12.sp, color = Marble.copy(.6f)); Spacer(Modifier.weight(1f)); Text(fmt(dur), fontSize = 12.sp, color = Marble.copy(.6f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassIcon(Icons.Rounded.Shuffle, "Shuffle", { c.go { q.setShuffle(!pv.shuffle) } }, if (pv.shuffle) Accent else Marble)
            GlassIcon(Icons.Rounded.SkipPrevious, "Previous", { c.go { q.previous() } })
            GlassIcon(Icons.Rounded.SkipNext, "Next", { c.go { q.next() } })
            GlassIcon(Icons.Rounded.Repeat, "Repeat ${pv.repeat}", { c.go { q.cycleRepeat() } }, if (pv.repeat != "OFF") Accent else Marble)
            GlassIcon(if (pv.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", { c.go { q.setLiked(!pv.liked) } }, if (pv.liked) Accent else Marble)
        }
        pv.error?.let { Text("$it (tap to dismiss)", color = Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).springPress { c.go { q.dismissError() } }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${pv.queue.size}", color = Marble, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (t != null) {
                DownloadButton(c, t); Spacer(Modifier.width(8.dp)); AddToPlaylistButton(c, t); Spacer(Modifier.width(8.dp))
            }
            GlassIcon(Icons.Rounded.ClearAll, "Clear queue", { c.go { q.clear() } }, size = 36.dp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pv.queue, key = { i, _ -> i }) { i, tr ->
                TrackRow(tr, highlight = i == pv.index, onClick = { c.go { q.skipTo(i) } }) {
                    GlassIcon(Icons.Rounded.Close, "Remove", { c.go { q.removeAt(i) } }, size = 36.dp)
                }
            }
        }
    }
}
