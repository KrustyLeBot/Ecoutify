package app.ecoutify.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Côté UI : pilote le service de lecture et expose l'état observable par Compose. */
class PlayerConnection(context: Context, private val app: EcoutifyApp) {
    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private val tracks = HashMap<String, Track>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val track = item?.mediaId?.let { tracks[it] }
            current = track
            if (track != null) {
                app.store.addToHistory(track)
                maybeAutoplay()
            }
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            isPlaying = c.isPlaying
            current = c.currentMediaItem?.mediaId?.let { tracks[it] }
            pending?.invoke()
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(queue: List<Track>, index: Int) {
        val action = {
            val c = controller!!
            queue.forEach { tracks[it.url] = it }
            c.setMediaItems(queue.map(::mediaItem), index, 0L)
            c.prepare()
            c.play()
        }
        if (controller == null) pending = action else action()
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    val positionMs: Long get() = controller?.currentPosition ?: 0L
    val durationMs: Long get() = controller?.duration?.takeIf { it > 0 } ?: 0L

    /** Fin de file atteinte : on ajoute des titres similaires, pour une écoute continue (« radio »). */
    private fun maybeAutoplay() {
        val c = controller ?: return
        val last = c.currentMediaItemIndex == c.mediaItemCount - 1
        val url = c.currentMediaItem?.mediaId ?: return
        if (!last) return
        scope.launch {
            val similar = runCatching { withContext(Dispatchers.IO) { app.catalog.related(url) } }.getOrNull() ?: return@launch
            val queued = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val fresh = similar.filter { it.url !in queued }.take(15)
            fresh.forEach { tracks[it.url] = it }
            c.addMediaItems(fresh.map(::mediaItem))
        }
    }

    private fun mediaItem(t: Track) = MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(t.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setArtworkUri(t.thumbnail?.let(Uri::parse))
                .build()
        )
        .build()
}
