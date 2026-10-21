package app.ecoutify.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.OkHttpDownloader
import java.io.IOException

/**
 * Background playback with a notification and lock-screen controls.
 * Each MediaItem's URI is the YouTube "watch" URL; at playback time it is resolved to the local
 * file if the track is downloaded, otherwise to the direct audio stream.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as EcoutifyApp
        val upstream = DefaultDataSource.Factory(
            this,
            OkHttpDataSource.Factory(app.http).setUserAgent(OkHttpDownloader.USER_AGENT),
        )
        val dataSource = ResolvingDataSource.Factory(upstream) { spec -> resolve(app, spec) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Always loop the queue: a finished queue (STATE_ENDED) would make a "play" from a car or Bluetooth
        // replay only the last track. And always shuffle: there is no sequential mode.
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = true
        session = MediaSession.Builder(this, player).build()
    }

    private fun resolve(app: EcoutifyApp, spec: DataSpec): DataSpec {
        val host = spec.uri.host.orEmpty()
        if (!host.endsWith("youtube.com") && host != "youtu.be") return spec
        val watchUrl = spec.uri.toString()
        app.store.downloadedFile(watchUrl)?.let { return spec.withUri(Uri.fromFile(it)) }
        return try {
            spec.withUri(Uri.parse(app.catalog.audioUrl(watchUrl)))
        } catch (e: Exception) {
            throw IOException(e.message ?: "Impossible de résoudre le flux audio", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiping the app away from the recent apps list stops the music. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.run {
            pause()
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
