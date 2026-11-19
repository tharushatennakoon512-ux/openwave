package com.openwave.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openwave.AppContainer
import com.openwave.core.model.AudioTrack
import com.openwave.core.model.DownloadState
import com.openwave.core.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun TrackRow(t: AudioTrack, highlight: Boolean = false, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().springPress(onClick).glass(RoundedCornerShape(18.dp), if (highlight) 1.9f else 1f).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(t.thumbnailUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, color = Marble, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Marble.copy(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun DownloadButton(c: AppContainer, t: AudioTrack) {
    val st = c.downloads.states.collectAsState().value[t.id]
    when (st) {
        is DownloadState.Completed -> GlassIcon(Icons.Rounded.DownloadDone, "Downloaded", {}, Accent, 36.dp)
        is DownloadState.Downloading, is DownloadState.Queued ->
            Box(Modifier.size(36.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Accent, strokeWidth = 2.dp) }
        else -> GlassIcon(Icons.Rounded.Download, "Download", { c.go { c.downloads.enqueue(t) } }, size = 36.dp)
    }
}

@Composable
fun AddToPlaylistButton(c: AppContainer, t: AudioTrack) {
    var open by remember { mutableStateOf(false) }
    val pls by c.playlists.state.collectAsState()
    Box {
        GlassIcon(Icons.Rounded.PlaylistAdd, "Add to playlist", { open = true }, size = 36.dp)
        DropdownMenu(open, { open = false }) {
            pls.keys.forEach { n -> DropdownMenuItem({ Text(n) }, { c.playlists.add(n, t); open = false }) }
            if ("Favorites" !in pls) DropdownMenuItem({ Text("New playlist: Favorites") }, { c.playlists.add("Favorites", t); open = false })
        }
    }
}

@Composable
fun SearchScreen(c: AppContainer) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(450); loading = true; err = null
        runCatching { c.search(q) }
            .onSuccess { results = it }
            .onFailure { if (it is CancellationException) throw it; err = "Search failed: ${it.message ?: "check your connection"}" }
        loading = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField(
            q, { q = it }, Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)), singleLine = true,
            placeholder = { Text("Songs, artists, links") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        err?.let { Text(it, color = Accent, fontSize = 13.sp) }
        if (!loading && q.isNotBlank() && results.isEmpty() && err == null) Text("No results for “$q”.", color = Marble.copy(.6f))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(results, key = { i, _ -> i }) { _, r ->
                TrackRow(r.track, onClick = { c.play(r.track) }) {
                    GlassIcon(Icons.Rounded.QueueMusic, "Add to queue", { c.go { c.queueManager.enqueue(r.track) } }, size = 36.dp)
                    Spacer(Modifier.width(6.dp)); DownloadButton(c, r.track)
                    Spacer(Modifier.width(6.dp)); AddToPlaylistButton(c, r.track)
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(c: AppContainer) {
    val pls by c.playlists.state.collectAsState()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().springPress { creating = true }.glass(RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = Accent); Spacer(Modifier.width(10.dp)); Text("New playlist", color = Marble)
        }
        if (pls.isEmpty()) Text("No playlists yet. Use the playlist button on any song to start one.", color = Marble.copy(.6f))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pls.keys.toList(), key = { it }) { n ->
                val tracks = pls[n].orEmpty()
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().springPress { expanded = if (expanded == n) null else n }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(n, color = Marble, fontWeight = FontWeight.SemiBold); Text("${tracks.size} tracks", color = Marble.copy(.6f), fontSize = 12.sp)
                        }
                        if (tracks.isNotEmpty()) GlassIcon(Icons.Rounded.PlayArrow, "Play", { c.playAll(tracks) }, size = 36.dp)
                        Spacer(Modifier.width(6.dp)); GlassIcon(Icons.Rounded.Delete, "Delete", { c.playlists.delete(n) }, size = 36.dp)
                    }
                    if (expanded == n) tracks.forEach { t ->
                        TrackRow(t, onClick = { c.play(t) }) { GlassIcon(Icons.Rounded.Close, "Remove", { c.playlists.remove(n, t.id) }, size = 36.dp) }
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { TextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) c.playlists.create(name.trim()); name = ""; creating = false }) { Text("Create") } },
        dismissButton = { TextButton({ creating = false }) { Text("Cancel") } },
    )
}

@Composable
fun DownloadsScreen(c: AppContainer) {
    val ctx = LocalContext.current
    val states by c.downloads.states.collectAsState()
    val done by remember { c.library.observeCompleted() }.collectAsState(emptyList())
    val bytes by c.library.totalStorageBytes.collectAsState(0L)          // ASSUMPTION: Flow<Long>
    val active = states.filter { it.value !is DownloadState.Idle && it.value !is DownloadState.Completed }.entries.toList()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${done.size} offline · ${Formatter.formatFileSize(ctx, bytes)}", color = Marble.copy(.7f), modifier = Modifier.glass(RoundedCornerShape(50)).padding(14.dp, 8.dp))
        if (active.isEmpty() && done.isEmpty()) Text("Nothing downloaded. Tap the download button on a song to keep it offline.", color = Marble.copy(.6f))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(active, key = { it.key }) { (id, s) ->
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.pool.value[id]?.title ?: id, color = Marble, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (s) {
                                    is DownloadState.Downloading -> "Downloading ${(s.progress.toFloat() * 100).toInt()}%"
                                    is DownloadState.Failed -> "Failed: ${s.reason}"
                                    is DownloadState.Paused -> "Paused"
                                    else -> "Queued"
                                }, color = Marble.copy(.6f), fontSize = 12.sp,
                            )
                        }
                        when (s) {
                            is DownloadState.Downloading -> GlassIcon(Icons.Rounded.Pause, "Pause", { c.go { c.downloads.pause(id) } }, size = 36.dp)
                            is DownloadState.Paused -> GlassIcon(Icons.Rounded.PlayArrow, "Resume", { c.go { c.downloads.resume(id) } }, size = 36.dp)
                            is DownloadState.Failed -> if (s.retryable) GlassIcon(Icons.Rounded.Refresh, "Retry", { c.go { c.downloads.retry(id) } }, size = 36.dp)
                            else -> {}
                        }
                        Spacer(Modifier.width(6.dp)); GlassIcon(Icons.Rounded.Close, "Cancel", { c.go { c.downloads.cancel(id) } }, size = 36.dp)
                    }
                    if (s is DownloadState.Downloading) LinearProgressIndicator({ s.progress.toFloat() }, Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                }
            }
            items(done, key = { it.id }) { t ->
                TrackRow(t, onClick = { c.play(t) }) {
                    GlassIcon(Icons.Rounded.Delete, "Delete download", { c.go { c.downloads.delete(t.id) } }, size = 36.dp)
                }
            }
        }
    }
}
