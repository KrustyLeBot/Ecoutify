package app.ecoutify.data

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tout est stocké localement (aucun compte) : historique, téléchargements, playlists, réglages. */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("ecoutify", Context.MODE_PRIVATE)

    val musicDir: File
        get() = (context.getExternalFilesDir("music") ?: File(context.filesDir, "music")).also { it.mkdirs() }

    // --- Réglages de lecture ---
    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(v) = prefs.edit().putBoolean("shuffle", v).apply()

    var repeat: Int
        get() = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        set(v) = prefs.edit().putInt("repeat", v).apply()

    // --- Historique ---
    fun history(): List<Track> = readList("history")

    fun addToHistory(t: Track) {
        writeList("history", (listOf(t) + history().filter { it.url != t.url }).take(60))
    }

    // --- Recommandations (gardées pour le mode hors ligne) ---
    fun cachedRecommendations(): List<Track> = readList("recs")
    fun saveRecommendations(list: List<Track>) = writeList("recs", list)

    // --- Téléchargements ---
    fun downloads(): List<Track> = readList("downloads").filter { fileOf(it.url) != null }

    fun downloadedFile(url: String): File? = fileOf(url)

    fun addDownload(t: Track, file: File) {
        prefs.edit().putString("file_${t.url}", file.name).apply()
        writeList("downloads", listOf(t) + readList("downloads").filter { it.url != t.url })
    }

    fun removeDownload(t: Track) {
        fileOf(t.url)?.delete()
        prefs.edit().remove("file_${t.url}").apply()
        writeList("downloads", readList("downloads").filter { it.url != t.url })
    }

    private fun fileOf(url: String): File? =
        prefs.getString("file_$url", null)?.let { File(musicDir, it) }?.takeIf { it.exists() }

    // --- Playlists ---
    fun playlists(): List<Playlist> {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val tracks = o.getJSONArray("tracks")
            Playlist(
                o.getString("id"),
                o.getString("name"),
                (0 until tracks.length()).map { i -> Track.fromJson(tracks.getJSONObject(i)) },
            )
        }
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name)
                    .put("tracks", JSONArray().also { a -> p.tracks.forEach { a.put(it.toJson()) } })
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        savePlaylists(playlists() + p)
        return p
    }

    fun deletePlaylist(id: String) = savePlaylists(playlists().filter { it.id != id })

    fun addToPlaylist(id: String, t: Track) = savePlaylists(playlists().map {
        if (it.id == id && it.tracks.none { x -> x.url == t.url }) it.copy(tracks = it.tracks + t) else it
    })

    fun removeFromPlaylist(id: String, t: Track) = savePlaylists(playlists().map {
        if (it.id == id) it.copy(tracks = it.tracks.filter { x -> x.url != t.url }) else it
    })

    // --- Utilitaires ---
    private fun readList(key: String): List<Track> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        return (0 until arr.length()).map { Track.fromJson(arr.getJSONObject(it)) }
    }

    private fun writeList(key: String, list: List<Track>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
