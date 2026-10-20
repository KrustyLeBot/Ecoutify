package app.ecoutify.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.ecoutify.R
import app.ecoutify.data.Playlist

/** Long-press on the app icon: start one of the first playlists in shuffle mode. */
object Shortcuts {
    private const val MAX = 4

    fun update(context: Context, playlists: List<Playlist>) {
        val limit = minOf(MAX, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
        val shortcuts = playlists.filter { it.tracks.isNotEmpty() }.take(limit).map { p ->
            ShortcutInfoCompat.Builder(context, "playlist_${p.id}")
                .setShortLabel(p.name.take(20))
                .setLongLabel("Aléatoire : ${p.name}".take(40))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_shuffle))
                .setIntent(PlayPlaylistActivity.intent(context, p.id).setAction(Intent.ACTION_VIEW))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
