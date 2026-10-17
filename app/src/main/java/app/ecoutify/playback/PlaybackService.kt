package app.ecoutify.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import app.ecoutify.data.YoutubeHttp
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
        session = MediaSession.Builder(this, player).build()
    }

    private fun resolve(app: EcoutifyApp, spec: DataSpec): DataSpec {
        val host = spec.uri.host.orEmpty()
        if (!host.endsWith("youtube.com") && host != "youtu.be") return spec
        val watchUrl = spec.uri.toString()
        app.store.downloadedFile(watchUrl)?.let { return spec.withUri(Uri.fromFile(it)) }
        val source = try {
            app.catalog.audioSource(watchUrl)
        } catch (e: Exception) {
            throw IOException(e.message ?: "Impossible de résoudre le flux audio", e)
        }
        if (!source.post) {
            // plain GET: ExoPlayer adds its own Range header
            return spec.buildUpon()
                .setUri(Uri.parse(YoutubeHttp.withParams(source.url)))
                .setHttpRequestHeaders(source.headers)
                .build()
        }
        // The byte range requested by ExoPlayer goes in the URL (`range=`), as NewPipe does.
        val unset = C.LENGTH_UNSET.toLong()
        val end = if (spec.length != unset) spec.position + spec.length - 1 else null
        return spec.buildUpon()
            .setUri(Uri.parse(YoutubeHttp.withParams(source.url, spec.position, end)))
            .setPosition(0)
            .setLength(unset)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(YoutubeHttp.POST_BODY)
            .setHttpRequestHeaders(source.headers)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
