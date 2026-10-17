package app.ecoutify.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Track
import app.ecoutify.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EcoutifyApp
    val player = PlayerConnection(application, app)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var recommendations by mutableStateOf<List<Track>>(emptyList())
        private set
    var loadingRecs by mutableStateOf(false)
        private set
    var downloaded by mutableStateOf(app.store.downloads())
        private set
    var downloading by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var message by mutableStateOf<String?>(null)

    init {
        refreshRecommendations()
        viewModelScope.launch {
            app.downloads.progress.collect {
                downloading = it
                downloaded = app.store.downloads()
            }
        }
        viewModelScope.launch {
            app.downloads.errors.collect { message = it ?: return@collect }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { app.catalog.search(q) }
            } catch (e: Exception) {
                message = "Recherche impossible : ${e.message}"
            } finally {
                searching = false
            }
        }
    }

    fun refreshRecommendations() {
        loadingRecs = true
        viewModelScope.launch {
            recommendations = app.recommender.forYou()
            loadingRecs = false
        }
    }

    fun play(queue: List<Track>, index: Int) = player.play(queue, index)

    /** Lance un titre puis enchaîne automatiquement sur des titres similaires. */
    fun radio(track: Track) = player.play(listOf(track), 0)

    fun download(t: Track) = app.downloads.start(t)

    fun deleteDownload(t: Track) {
        app.store.removeDownload(t)
        downloaded = app.store.downloads()
    }
}
