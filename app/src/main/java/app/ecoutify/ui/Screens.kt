package app.ecoutify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ecoutify.data.Track
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun EcoutifyTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Pour toi", Icons.Default.Home),
    Search("Recherche", Icons.Default.Search),
    Library("Hors ligne", Icons.Default.DownloadForOffline),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcoutifyScreen(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.Home) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                MiniPlayer(vm)
                NavigationBar {
                    Tab.entries.forEach {
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(it.icon, it.label) },
                            label = { Text(it.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(vm)
                Tab.Search -> SearchScreen(vm)
                Tab.Library -> LibraryScreen(vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pour toi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::refreshRecommendations) { Icon(Icons.Default.Refresh, "Actualiser") }
        }
        if (vm.loadingRecs) LinearProgressIndicator(Modifier.fillMaxWidth())
        TrackList(vm, vm.recommendations)
    }
}

@Composable
private fun SearchScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Titre, artiste, album…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        TrackList(vm, vm.results)
    }
}

@Composable
private fun LibraryScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("Téléchargés", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        if (vm.downloaded.isEmpty()) {
            Text("Rien ici pour l'instant. Utilise ⬇ sur un titre pour l'écouter hors ligne.", Modifier.padding(16.dp))
        }
        TrackList(vm, vm.downloaded, offline = true)
    }
}

@Composable
private fun TrackList(vm: MainViewModel, tracks: List<Track>, offline: Boolean = false) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { i, t -> "${t.url}#$i" }) { index, track ->
            val isDownloaded = vm.downloaded.any { it.url == track.url }
            TrackRow(
                track = track,
                playing = vm.player.current?.url == track.url,
                downloadProgress = vm.downloading[track.url],
                downloaded = isDownloaded,
                onClick = { vm.play(tracks, index) },
                onRadio = { vm.radio(track) },
                onDownload = { if (offline) vm.deleteDownload(track) else vm.download(track) },
                offline = offline,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    playing: Boolean,
    downloadProgress: Int?,
    downloaded: Boolean,
    offline: Boolean,
    onClick: () -> Unit,
    onRadio: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRadio) { Icon(Icons.Default.Radio, "Titres similaires") }
        when {
            downloadProgress != null -> CircularProgressIndicator(
                progress = { downloadProgress / 100f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
            )
            offline -> IconButton(onClick = onDownload) { Icon(Icons.Default.Delete, "Supprimer") }
            downloaded -> Icon(Icons.Default.DownloadDone, "Téléchargé", Modifier.padding(12.dp))
            else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Télécharger") }
        }
    }
}

@Composable
private fun MiniPlayer(vm: MainViewModel) {
    val player = vm.player
    val track = player.current ?: return
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(track, player.isPlaying) {
        while (true) {
            val d = player.durationMs
            progress = if (d > 0) player.positionMs.toFloat() / d else 0f
            delay(500)
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = track.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = player::previous) { Icon(Icons.Default.SkipPrevious, "Précédent") }
                IconButton(onClick = player::togglePlay) {
                    Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture/pause")
                }
                IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, "Suivant") }
            }
        }
    }
}
