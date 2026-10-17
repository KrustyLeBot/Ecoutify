package app.ecoutify.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Track

/**
 * Côté UI : pilote le service de lecture et expose l'état observable par Compose.
 * La file est simplement la liste d'où on lance le titre ; aléatoire et répétition sont ceux d'ExoPlayer.
 */
class PlayerConnection(
    context: Context,
    private val app: EcoutifyApp,
    private val isOnline: () -> Boolean,
    private val onMessage: (String) -> Unit,
) {
    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(app.store.shuffle)
        private set
    var repeat by mutableIntStateOf(app.store.repeat)
        private set

    private var controller: MediaController? = null
    private var pending: ((MediaController) -> Unit)? = null
    private var errorStreak = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) errorStreak = 0
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            current = item?.let(::trackOf)
            current?.let { app.store.addToHistory(it) }
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            shuffle = enabled
        }

        override fun onRepeatModeChanged(mode: Int) {
            repeat = mode
        }

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            errorStreak++
            if (errorStreak <= 5 && c.hasNextMediaItem()) {
                onMessage("Titre illisible, passage au suivant")
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            } else {
                onMessage("Lecture impossible (${error.errorCodeName}). Vérifie ta connexion ou télécharge le titre.")
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
            if (c.mediaItemCount == 0) {
                c.shuffleModeEnabled = shuffle
                c.repeatMode = repeat
            } else {
                shuffle = c.shuffleModeEnabled
                repeat = c.repeatMode
            }
            isPlaying = c.isPlaying
            current = c.currentMediaItem?.let(::trackOf)
            pending?.invoke(c)
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    /** Joue [queue] à partir de [index]. Hors ligne, seuls les titres téléchargés restent dans la file. */
    fun play(queue: List<Track>, index: Int) {
        val tapped = queue.getOrNull(index) ?: return
        val playable = if (isOnline()) queue else queue.filter { app.store.downloadedFile(it.url) != null }
        val start = playable.indexOfFirst { it.url == tapped.url }
        if (start < 0) {
            onMessage("Hors ligne : ce titre n'est pas téléchargé")
            return
        }
        val action = { c: MediaController ->
            c.setMediaItems(playable.map(::mediaItem), start, 0L)
            c.prepare()
            c.play()
        }
        controller?.let(action) ?: run { pending = action }
    }

    fun playShuffled(queue: List<Track>) {
        if (queue.isEmpty()) return
        setShuffle(true)
        play(queue, queue.indices.random())
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() { controller?.seekToNextMediaItem() }

    fun previous() {
        controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun setShuffle(on: Boolean) {
        app.store.shuffle = on
        shuffle = on
        controller?.shuffleModeEnabled = on
    }

    fun cycleRepeat() {
        val next = when (repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        app.store.repeat = next
        repeat = next
        controller?.repeatMode = next
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0L
    val durationMs: Long get() = controller?.duration?.takeIf { it > 0 } ?: 0L

    private fun trackOf(item: MediaItem) = Track(
        url = item.mediaId,
        title = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        durationSec = 0,
        thumbnail = null,
    )

    private fun mediaItem(t: Track) = MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(t.url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build())
        .build()
}
