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
 * Lecture en arrière-plan avec notification et contrôles écran verrouillé.
 * Chaque MediaItem a pour URI l'URL « watch » YouTube ; elle est résolue en fichier local
 * si le morceau est téléchargé, sinon en flux audio direct au moment de la lecture.
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
        val streamUrl = try {
            app.catalog.audioUrl(watchUrl)
        } catch (e: Exception) {
            throw IOException("Impossible de résoudre le flux audio", e)
        }
        // La plage demandée par ExoPlayer passe dans l'URL (`range=`), comme le fait NewPipe.
        val unset = C.LENGTH_UNSET.toLong()
        val end = if (spec.length != unset) spec.position + spec.length - 1 else null
        return spec.buildUpon()
            .setUri(Uri.parse(YoutubeHttp.withParams(streamUrl, spec.position, end)))
            .setPosition(0)
            .setLength(unset)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(YoutubeHttp.POST_BODY)
            .setHttpRequestHeaders(YoutubeHttp.headers(streamUrl))
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
