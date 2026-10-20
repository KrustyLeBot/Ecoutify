package app.ecoutify.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.ecoutify.EcoutifyApp
import app.ecoutify.data.Connectivity
import app.ecoutify.playback.PlayerConnection

/**
 * Invisible launcher target (widget tap or app-icon shortcut): starts the given playlist in shuffle
 * mode through the playback service, then closes itself. Nothing is shown on screen.
 */
class PlayPlaylistActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var connection: PlayerConnection? = null
    private var ticks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EcoutifyApp
        val playlist = app.store.playlists().firstOrNull { it.id == intent.getStringExtra(EXTRA_PLAYLIST_ID) }
        if (playlist == null || playlist.tracks.isEmpty()) {
            Toast.makeText(this, "Playlist introuvable ou vide", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val appContext = applicationContext
        val player = PlayerConnection(
            appContext, app,
            isOnline = { Connectivity.isOnline(appContext) },
            onMessage = { Toast.makeText(appContext, it, Toast.LENGTH_LONG).show() },
        )
        connection = player
        player.playShuffled(playlist.tracks)
        waitForPlayback()
    }

    /** Closes once playback has started (or after ~5 s), so the commands have time to reach the service. */
    private fun waitForPlayback() {
        handler.postDelayed({
            val player = connection
            if (player != null && (player.isPlaying || ++ticks >= 16)) {
                finish()
            } else {
                waitForPlayback()
            }
        }, 300)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        connection?.release()
        connection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, PlayPlaylistActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
