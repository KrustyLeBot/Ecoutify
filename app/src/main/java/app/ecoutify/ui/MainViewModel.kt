package app.ecoutify.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.ecoutify.data.Downloads
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Playlist
import app.ecoutify.sync.SkipReason
import app.ecoutify.sync.SyncState
import app.ecoutify.sync.SynchrotronClient
import app.ecoutify.suite.SuiteUpdates
import app.ecoutify.data.Track
import app.ecoutify.playback.PlayerConnection
import app.ecoutify.widget.PlaylistWidgetProvider
import app.ecoutify.widget.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Intent
import kotlinx.coroutines.CancellationException
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcoutifyApp
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)

    var online by mutableStateOf(checkOnline())
        private set
    var message by mutableStateOf<String?>(null)
    var storageOk by mutableStateOf(app.store.hasStorageAccess())
        private set

    val player = PlayerConnection(application, app, isOnline = ::checkOnline) { message = it }

    var query by mutableStateOf("")
    /** Incremented each time text is shared to the app, so the UI can jump to the search tab. */
    var shareRequest by mutableIntStateOf(0)
        private set
    private var coverVersion by mutableIntStateOf(0)

    val updates = SuiteUpdates(app, app.updater, viewModelScope)
    val syncFolder: String get() = app.store.syncPath
    private var coverJob: Job? = null
    private var runningDownloads = emptyMap<String, Int>()
    private var queuedDownloads = emptySet<String>()

    /** Import of a YouTube playlist: null = dialog closed, otherwise the text prefilled in it. */
    var importDialog by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set
    var results by mutableStateOf<List<Track>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var recommendations by mutableStateOf(app.store.cachedRecommendations())
        private set
    var loadingRecs by mutableStateOf(false)
        private set
    var downloaded by mutableStateOf(app.store.downloads())
        private set
    var downloading by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var playlists by mutableStateOf(app.store.playlists())
        private set

    init {
        refreshStorage()
        app.downloads.scheduleSync()
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
                ensureCovers()
            }

            override fun onLost(network: Network) {
                online = checkOnline()
            }
        })
        refreshRecommendations()
        viewModelScope.launch {
            app.downloads.progress.collect {
                runningDownloads = it
                downloading = queuedDownloads.associateWith { 0 } + it
                downloaded = app.store.downloads()
            }
        }
        viewModelScope.launch {
            // downloads waiting in the background queue (still there after the app was closed and reopened)
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Downloads.QUEUE).collect { infos ->
                queuedDownloads = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
                    .flatMap { it.tags }.filter { it.startsWith(Downloads.TAG_PREFIX) }.map { it.removePrefix(Downloads.TAG_PREFIX) }.toSet()
                downloading = queuedDownloads.associateWith { 0 } + runningDownloads
                downloaded = app.store.downloads()
            }
        }
        viewModelScope.launch {
            // keep the widgets and the app-icon shortcuts in step with the playlists
            snapshotFlow { playlists }.collect {
                Shortcuts.update(app, it)
                PlaylistWidgetProvider.updateAll(app)
            }
        }
        viewModelScope.launch {
            app.covers.version.collect { coverVersion = it }
        }
        viewModelScope.launch {
            app.downloads.errors.collect { e -> if (e != null) message = e }
        }
    }

    /** Call when the app comes back to the foreground: picks up the all-files-access permission. */
    fun refreshStorage() {
        storageOk = app.store.hasStorageAccess()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.store.migratePrivateToPublic() }
            reloadFromDisk()
        }
    }

    private suspend fun reloadFromDisk(adopt: Boolean = false) {
        withContext(Dispatchers.IO) { if (adopt) adoptOrphanDownloads() }
        downloaded = app.store.downloads()
        playlists = app.store.playlists()
        ensureCovers()
    }

    // --- Proton Drive sync through Synchrotron ---

    /** A sync is running: playlists and downloads are read-only (playback still works) until it ends. */
    var syncing by mutableStateOf(false)
        private set
    private var stopSync: (() -> Unit)? = null

    /** Asks Synchrotron for a sync so the library is up to date when the app opens. */
    fun syncOnOpen() {
        if (syncing || !app.store.hasStorageAccess()) return
        stopSync?.invoke()
        stopSync = SynchrotronClient.syncOnOpen(app, app.store.syncPath) { state ->
            when (state) {
                SyncState.Syncing -> syncing = true
                is SyncState.Done -> {
                    syncing = false
                    viewModelScope.launch {
                        // Orphan adoption waits for the sync: a track synced before playlists.json is not an orphan.
                        reloadFromDisk(adopt = true)
                        if (state.changes > 0) message = "Bibliothèque synchronisée (${state.changes} changement${if (state.changes > 1) "s" else ""})"
                    }
                }
                is SyncState.Failed -> { syncing = false; message = "Échec de la synchro Proton Drive"; adoptLater() }
                SyncState.TimedOut -> { syncing = false; message = "Synchro longue : elle continue en arrière-plan" }
                is SyncState.Skipped -> {
                    syncing = false
                    if (state.reason == SkipReason.NEEDS_SETUP) message = "Termine la configuration dans Synchrotron"
                    adoptLater()
                }
            }
        }
    }

    private fun adoptLater() {
        viewModelScope.launch { reloadFromDisk(adopt = true) }
    }

    override fun onCleared() {
        stopSync?.invoke()
    }

    private fun blockedBySync(): Boolean {
        if (syncing) message = "Synchronisation en cours…"
        return syncing
    }

    /** Image to show for [t]: the saved cover file when there is one, else its online image. */
    fun coverModel(t: Track): String? {
        if (coverVersion < 0) return null // read so that Compose refreshes when a cover gets saved
        return app.covers.model(t)
    }

    /** Fetches, in the background, the covers missing for playlist and downloaded tracks (needs a connection). */
    private fun ensureCovers() {
        if (coverJob?.isActive == true || !checkOnline()) return
        coverJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.covers.ensure(app.store.playlists().flatMap { it.tracks } + app.store.downloads()) }
        }
    }

    private fun checkOnline(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // --- Search & recommendations ---
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { app.catalog.search(q) }
                if (results.isEmpty()) message = "Aucun résultat"
            } catch (e: Exception) {
                message = "Recherche impossible : ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    /** Searches for text shared from another app (links and the " - " separator are dropped). */
    fun searchShared(raw: String) {
        val link = Regex("https?://\\S+").find(raw)?.value
        if (link != null && link.contains("/playlist?") && link.contains("list=")) {
            importDialog = link // a shared playlist link: offer to import it
            shareRequest++
            return
        }
        val cleaned = raw.replace(Regex("https?://\\S+"), " ").replace(" - ", " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return
        query = cleaned
        shareRequest++
        search()
    }

    fun refreshRecommendations() {
        if (loadingRecs) return
        loadingRecs = true
        viewModelScope.launch {
            val fresh = runCatching { app.recommender.forYou() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                recommendations = fresh
                app.store.saveRecommendations(fresh)
            }
            loadingRecs = false
        }
    }

    // --- "What is playing?" ---
    enum class Listening { RECORDING, IDENTIFYING }

    /** Song recognition in progress (null when idle). */
    var listening by mutableStateOf<Listening?>(null)
        private set
    private var listenJob: Job? = null

    /** Listens to the music around, then searches YouTube for the song Shazam found. */
    fun recognize() {
        if (listening != null) {
            listenJob?.cancel()
            listening = null
            return
        }
        if (!online) {
            message = "La reconnaissance nécessite une connexion"
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        listening = Listening.RECORDING
        listenJob = viewModelScope.launch {
            try {
                val match = app.recognizer.listen { listening = Listening.IDENTIFYING }
                if (match == null) {
                    message = "Morceau non reconnu"
                } else {
                    query = listOf(match.title, match.artist).filter { it.isNotEmpty() }.joinToString(" ")
                    message = "Trouvé : ${match.title}" + if (match.artist.isNotEmpty()) " — ${match.artist}" else ""
                    search()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Reconnaissance impossible : ${e.message}"
            } finally {
                listening = null
            }
        }
    }

    // --- Playback ---
    fun play(queue: List<Track>, index: Int) = player.play(queue, index)
    fun playShuffled(queue: List<Track>) = player.playShuffled(queue)

    // --- Downloads ---
    fun download(t: Track) {
        if (blockedBySync()) return
        if (!online) message = "Hors ligne : téléchargement impossible" else app.downloads.start(t)
    }

    fun downloadAll(list: List<Track>) = list.forEach(::download)

    fun deleteDownload(t: Track) {
        if (blockedBySync()) return
        app.store.removeDownload(t)
        downloaded = app.store.downloads()
    }

    fun isDownloaded(t: Track) = downloaded.any { it.url == t.url }

    // --- Playlists ---
    fun createPlaylist(name: String, thenAdd: Track? = null) {
        if (name.isBlank() || blockedBySync()) return
        val p = app.store.createPlaylist(name)
        thenAdd?.let { app.store.addToPlaylist(p.id, it) }
        playlists = app.store.playlists()
    }

    /** "Keep offline": download every track of the playlist automatically (on Wi-Fi), now and as it grows. */
    fun setKeepOffline(p: Playlist, on: Boolean) {
        if (blockedBySync()) return
        app.store.setKeepOffline(p.id, on)
        playlists = app.store.playlists()
        if (on) {
            app.downloads.scheduleSync()
            message = "Les titres seront téléchargés automatiquement en Wi-Fi"
        }
    }

    /** Creates a playlist from a YouTube / YouTube Music playlist link. */
    fun importPlaylist(link: String) {
        if (importing || blockedBySync()) return
        importing = true
        viewModelScope.launch {
            try {
                val (name, tracks) = withContext(Dispatchers.IO) { app.catalog.playlist(link.trim()) }
                if (tracks.isEmpty()) {
                    message = "Aucun titre trouvé dans cette playlist"
                } else {
                    app.store.createPlaylistWith(name, tracks)
                    playlists = app.store.playlists()
                    message = "Playlist « $name » importée (${tracks.size} titres)"
                    ensureCovers()
                }
            } catch (e: Exception) {
                message = "Import impossible : ${e.message}"
            } finally {
                importing = false
            }
        }
    }

    fun renamePlaylist(p: Playlist, name: String) {
        if (name.isBlank() || blockedBySync()) return
        app.store.renamePlaylist(p.id, name)
        playlists = app.store.playlists()
    }

    fun deletePlaylist(p: Playlist) {
        if (blockedBySync()) return
        app.store.deletePlaylist(p.id)
        playlists = app.store.playlists()
        p.tracks.forEach(::deleteIfInNoPlaylist)
    }

    fun addToPlaylist(p: Playlist, t: Track) {
        if (blockedBySync()) return
        app.store.addToPlaylist(p.id, t)
        playlists = app.store.playlists()
        if (p.keepOffline) app.downloads.scheduleSync()
        message = "Ajouté à « ${p.name} »"
        viewModelScope.launch(Dispatchers.IO) { runCatching { app.covers.save(t) } }
    }

    fun removeFromPlaylist(p: Playlist, t: Track) {
        if (blockedBySync()) return
        app.store.removeFromPlaylist(p.id, t)
        playlists = app.store.playlists()
        deleteIfInNoPlaylist(t)
    }

    /** Downloads only exist for tracks in a playlist: free the file once no playlist has the track. */
    private fun deleteIfInNoPlaylist(t: Track) {
        if (playlists.none { pl -> pl.tracks.any { it.url == t.url } }) {
            if (isDownloaded(t)) deleteDownload(t)
            app.store.coverFile(t.url)?.delete()
        }
    }

    /**
     * Files in `Ecoutify/music/` that no playlist references (e.g. downloaded before downloads were
     * tied to playlists, or after a reinstall that lost the playlist file) go into a "Récupérés" playlist
     * so they stay reachable.
     */
    private fun adoptOrphanDownloads() {
        val inPlaylists = app.store.playlists().flatMap { it.tracks }.map { it.url }.toSet()
        val orphans = app.store.downloads().filter { it.url !in inPlaylists }
        if (orphans.isEmpty()) return
        val target = app.store.playlists().firstOrNull { it.name == "Récupérés" } ?: app.store.createPlaylist("Récupérés")
        orphans.forEach { app.store.addToPlaylist(target.id, it) }
    }
}
