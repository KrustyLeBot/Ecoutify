package app.ecoutify.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Track

/** Builds the player's queue items from tracks. The URI is the YouTube "watch" URL, resolved at playback time. */
object QueueItems {
    fun mediaItem(app: EcoutifyApp, t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.url)
        .setUri(t.url)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist)
                .setArtworkUri(artworkOf(app, t))
                .build()
        )
        .build()

    /** Cover for the notification / lock screen: the saved file if any, else the online image. */
    private fun artworkOf(app: EcoutifyApp, t: Track): Uri? =
        app.store.coverFile(t.url)?.takeIf { it.exists() }?.let(Uri::fromFile) ?: t.thumbnail?.let(Uri::parse)
}
