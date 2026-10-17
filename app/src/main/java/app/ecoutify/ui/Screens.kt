package app.ecoutify.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.ecoutify.data.Playlist
import app.ecoutify.data.Track
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Pour toi", Icons.Default.AutoAwesome),
    Search("Recherche", Icons.Default.Search),
    Library("Bibliothèque", Icons.Default.LibraryMusic),
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun EcoutifyScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    LaunchedEffect(vm.shareRequest) {
        if (vm.shareRequest > 0) {
            tab = Tab.Search
            showPlayer = false
        }
    }
    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = !showPlayer && tab == Tab.Library && openList != null) { openList = null }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(vm, onOpen = { showPlayer = true })
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach {
                            NavigationBarItem(
                                selected = tab == it,
                                onClick = {
                                    if (tab == it && it == Tab.Library) openList = null
                                    tab = it
                                },
                                icon = { Icon(it.icon, it.label) },
                                label = { Text(it.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (!vm.storageOk) StorageBanner()
                if (!vm.online) OfflineBanner()
                when (tab) {
                    Tab.Home -> HomeScreen(vm)
                    Tab.Search -> SearchScreen(vm)
                    Tab.Library -> LibraryScreen(vm, openList) { openList = it }
                }
            }
        }
        if (showPlayer) PlayerScreen(vm, onClose = { showPlayer = false })
    }
}

@Composable
private fun StorageBanner() {
    val context = LocalContext.current
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Autorise l'accès aux fichiers pour garder tes musiques dans le dossier Ecoutify, même après désinstallation.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                (context as? Activity)?.requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2,
                )
            }
        }) { Text("Autoriser") }
    }
    }
}

@Composable
private fun OfflineBanner() {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.WifiOff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Hors ligne — seuls les titres téléchargés sont lisibles", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

// ---------------------------------------------------------------- For you

@Composable
private fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            "Pour toi",
            if (vm.downloaded.isEmpty()) "Télécharge des titres pour affiner les suggestions" else "D'après tes titres téléchargés et tes playlists",
        ) {
            IconButton(onClick = vm::refreshRecommendations, enabled = vm.online) {
                Icon(Icons.Default.Refresh, "Actualiser")
            }
        }
        if (vm.loadingRecs) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.recommendations.isEmpty() && !vm.loadingRecs) {
            EmptyHint(if (vm.online) "Aucune suggestion pour l'instant." else "Les suggestions reviendront avec la connexion.")
        }
        TrackList(vm, vm.recommendations)
    }
}

// ---------------------------------------------------------------- Search

@Composable
private fun SearchScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Recherche")
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Titre, artiste…") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Effacer") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        Spacer(Modifier.height(8.dp))
        if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!vm.online) EmptyHint("La recherche nécessite une connexion.")
        TrackList(vm, vm.results)
    }
}

// ---------------------------------------------------------------- Library

@Composable
private fun LibraryScreen(vm: MainViewModel, openList: String?, setOpen: (String?) -> Unit) {
    val playlist = vm.playlists.firstOrNull { it.id == openList }
    when {
        playlist != null -> ListDetail(vm, playlist.name, playlist.tracks, playlist, setOpen)
        else -> LibraryHome(vm, setOpen)
    }
}

@Composable
private fun LibraryHome(vm: MainViewModel, setOpen: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Bibliothèque") {
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nouvelle playlist") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.playlists, key = { it.id }) { p ->
                LibraryRow(
                    icon = Icons.Default.LibraryMusic, tint = MaterialTheme.colorScheme.primary,
                    title = p.name,
                    subtitle = "${p.tracks.count(vm::isDownloaded)}/${p.tracks.size} hors ligne",
                    onClick = { setOpen(p.id) },
                    onLongClick = { toDelete = p },
                )
            }
            if (vm.playlists.isEmpty()) {
                item { EmptyHint("Crée une playlist avec +, ajoute-y des titres (menu ⋮), puis télécharge-les.") }
            }
        }
    }
    if (creating) NameDialog("Nouvelle playlist", onDismiss = { creating = false }) { vm.createPlaylist(it) }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les titres téléchargés restent sur le téléphone.") },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(p); toDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    icon: ImageVector, tint: Color, title: String, subtitle: String,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListDetail(vm: MainViewModel, title: String, tracks: List<Track>, playlist: Playlist, setOpen: (String?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { setOpen(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${tracks.count(vm::isDownloaded)}/${tracks.size} hors ligne",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.play(tracks, 0) }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Lecture")
            }
            FilledTonalButton(onClick = { vm.playShuffled(tracks) }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Aléatoire")
            }
            IconButton(onClick = { vm.downloadAll(tracks) }, enabled = tracks.any { !vm.isDownloaded(it) }) {
                Icon(Icons.Default.Download, "Tout télécharger")
            }
        }
        if (tracks.isEmpty()) EmptyHint("Playlist vide. Ajoute des titres depuis le menu ⋮ d'un titre.")
        TrackList(vm, tracks, playlist = playlist)
    }
}

// ---------------------------------------------------------------- Track list

@Composable
private fun EmptyHint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TrackList(vm: MainViewModel, tracks: List<Track>, playlist: Playlist? = null) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { i, t -> "${t.url}#$i" }) { index, track ->
            TrackRow(vm, track, tracks, index, playlist)
        }
    }
}

@Composable
private fun TrackRow(vm: MainViewModel, track: Track, queue: List<Track>, index: Int, playlist: Playlist?) {
    val downloaded = vm.isDownloaded(track)
    val progress = vm.downloading[track.url]
    val playing = vm.player.current?.url == track.url
    val unavailable = !vm.online && !downloaded
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .alpha(if (unavailable) 0.4f else 1f)
            .clickable { vm.play(queue, index) }
            .padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (playing) Icon(Icons.Default.GraphicEq, "En lecture", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOf(track.artist, if (track.durationSec > 0) formatTime(track.durationSec * 1000) else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            progress != null -> CircularProgressIndicator(
                progress = { progress / 100f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
            )
            downloaded -> Icon(Icons.Default.DownloadDone, "Téléchargé", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Ajouter à une playlist") }, onClick = { menu = false; picking = true })
                // Downloads are only possible for tracks that are in a playlist.
                if (playlist != null) {
                    if (downloaded) {
                        DropdownMenuItem(text = { Text("Supprimer le téléchargement") }, onClick = { menu = false; vm.deleteDownload(track) })
                    } else {
                        DropdownMenuItem(text = { Text("Télécharger") }, onClick = { menu = false; vm.download(track) })
                    }
                    DropdownMenuItem(text = { Text("Retirer de la playlist") }, onClick = { menu = false; vm.removeFromPlaylist(playlist, track) })
                }
            }
        }
    }
    if (picking) PlaylistPicker(vm, track) { picking = false }
}

@Composable
private fun PlaylistPicker(vm: MainViewModel, track: Track, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("Nouvelle playlist", onDismiss = onDismiss) { vm.createPlaylist(it, thenAdd = track) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à…") },
        text = {
            Column {
                vm.playlists.forEach { p ->
                    TextButton(onClick = { vm.addToPlaylist(p, track); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp))
                    Text("Nouvelle playlist", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun NameDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nom") }) },
        confirmButton = {
            TextButton(onClick = { onConfirm(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- Player

@Composable
private fun rememberProgress(vm: MainViewModel): State<Pair<Long, Long>> {
    val state = remember { mutableStateOf(0L to 0L) }
    val track = vm.player.current
    LaunchedEffect(track, vm.player.isPlaying) {
        while (true) {
            state.value = vm.player.positionMs to vm.player.durationMs
            delay(500)
        }
    }
    return state
}

@Composable
private fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val player = vm.player
    val track = player.current ?: return
    val (pos, dur) = rememberProgress(vm).value
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(
                progress = { if (dur > 0) pos.toFloat() / dur else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = player::togglePlay) {
                    Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture/pause", Modifier.size(30.dp))
                }
                IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, "Suivant") }
            }
        }
    }
}

@Composable
private fun PlayerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val player = vm.player
    val track = player.current
    val (pos, dur) = rememberProgress(vm).value
    var dragging by remember { mutableStateOf<Float?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        IconButton(onClick = onClose, Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Fermer", Modifier.size(32.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            track?.title ?: "Rien en lecture", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(track?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(28.dp))
        Slider(
            value = dragging ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
            enabled = dur > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.let { (it * dur).toLong() } ?: pos), style = MaterialTheme.typography.bodySmall, color = dim)
            Spacer(Modifier.weight(1f))
            Text(formatTime(dur), style = MaterialTheme.typography.bodySmall, color = dim)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.enableShuffle(!player.shuffle) }) {
                Icon(Icons.Default.Shuffle, "Aléatoire", tint = if (player.shuffle) accent else dim)
            }
            IconButton(onClick = player::previous, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, "Précédent", Modifier.size(36.dp))
            }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(accent).clickable(onClick = player::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture/pause",
                    Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            IconButton(onClick = player::next, Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, "Suivant", Modifier.size(36.dp))
            }
            IconButton(onClick = player::cycleRepeat) {
                Icon(
                    if (player.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Répéter", tint = if (player.repeat == Player.REPEAT_MODE_OFF) dim else accent,
                )
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
