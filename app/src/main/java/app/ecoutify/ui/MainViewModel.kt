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
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Playlist
import app.ecoutify.data.Track
import app.ecoutify.playback.PlayerConnection
import app.ecoutify.widget.PlaylistWidgetProvider
import app.ecoutify.widget.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var coverJob: Job? = null
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
                downloading = it
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
            withContext(Dispatchers.IO) {
                app.store.migratePrivateToPublic()
                adoptOrphanDownloads()
            }
            downloaded = app.store.downloads()
            playlists = app.store.playlists()
            ensureCovers()
        }
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

    // --- Playback ---
    fun play(queue: List<Track>, index: Int) = player.play(queue, index)
    fun playShuffled(queue: List<Track>) = player.playShuffled(queue)

    // --- Downloads ---
    fun download(t: Track) {
        if (!online) message = "Hors ligne : téléchargement impossible" else app.downloads.start(t)
    }

    fun downloadAll(list: List<Track>) = list.forEach(::download)

    fun deleteDownload(t: Track) {
        app.store.removeDownload(t)
        downloaded = app.store.downloads()
    }

    fun isDownloaded(t: Track) = downloaded.any { it.url == t.url }

    // --- Playlists ---
    fun createPlaylist(name: String, thenAdd: Track? = null) {
        if (name.isBlank()) return
        val p = app.store.createPlaylist(name)
        thenAdd?.let { app.store.addToPlaylist(p.id, it) }
        playlists = app.store.playlists()
    }

    fun renamePlaylist(p: Playlist, name: String) {
        if (name.isBlank()) return
        app.store.renamePlaylist(p.id, name)
        playlists = app.store.playlists()
    }

    fun deletePlaylist(p: Playlist) {
        app.store.deletePlaylist(p.id)
        playlists = app.store.playlists()
        p.tracks.forEach(::deleteIfInNoPlaylist)
    }

    fun addToPlaylist(p: Playlist, t: Track) {
        app.store.addToPlaylist(p.id, t)
        playlists = app.store.playlists()
        message = "Ajouté à « ${p.name} »"
        viewModelScope.launch(Dispatchers.IO) { runCatching { app.covers.save(t) } }
    }

    fun removeFromPlaylist(p: Playlist, t: Track) {
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
