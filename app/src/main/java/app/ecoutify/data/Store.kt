package app.ecoutify.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Tout est local (aucun compte).
 *
 * Les musiques et les playlists vivent dans le dossier public `Ecoutify/` du stockage interne
 * (à la racine, pas dans « Musique ») : il survit à la désinstallation et tout y est redétecté
 * à la réinstallation. Pour cela l'appli demande « l'accès à tous les fichiers » ; tant qu'il
 * n'est pas accordé, un dossier privé est utilisé, puis son contenu est déplacé au moment de l'autorisation.
 *
 * - `Ecoutify/musique/<artiste> - <titre> [<id YouTube>].m4a` : un fichier par titre téléchargé ;
 *   l'artiste, le titre et l'identifiant se relisent depuis le nom du fichier.
 * - `Ecoutify/playlists.json` : les playlists.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("ecoutify", Context.MODE_PRIVATE)

    private val privateRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Ecoutify")

    private val publicRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "Ecoutify")

    /** L'accès au dossier public `Ecoutify/` est-il autorisé ? */
    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private val root: File
        get() = (if (hasStorageAccess()) publicRoot else privateRoot).also { it.mkdirs() }

    val musicDir: File
        get() = File(root, "musique").also { it.mkdirs() }

    private val playlistsFile: File
        get() = File(root, "playlists.json")

    /** Déplace vers le dossier public ce qui a été téléchargé avant l'autorisation d'accès. */
    fun migratePrivateToPublic() {
        if (!hasStorageAccess() || !privateRoot.exists()) return
        runCatching {
            File(privateRoot, "musique").listFiles()?.forEach { f ->
                val target = File(musicDir, f.name)
                if (!target.exists()) f.copyTo(target)
                f.delete()
            }
            val privatePlaylists = File(privateRoot, "playlists.json")
            if (privatePlaylists.exists() && !playlistsFile.exists()) privatePlaylists.copyTo(playlistsFile)
            privatePlaylists.delete()
        }
    }

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

    // --- Téléchargements (relus depuis le dossier à chaque fois) ---
    fun downloads(): List<Track> =
        (musicDir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.mapNotNull { trackOf(it) }

    fun downloadedFile(url: String): File? {
        val id = videoId(url) ?: return null
        return (musicDir.listFiles() ?: emptyArray()).firstOrNull { fileId(it) == id }
    }

    /** Fichier de destination pour [t] (écrit d'abord en `.part`, renommé une fois complet). */
    fun fileFor(t: Track, ext: String): File {
        val id = videoId(t.url) ?: error("URL inconnue")
        fun clean(x: String) = x.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").replace(" - ", " ").trim()
        val base = listOf(clean(t.artist), clean(t.title)).filter { it.isNotEmpty() }.joinToString(" - ").take(100).trim()
        return File(musicDir, "$base [$id].$ext")
    }

    fun removeDownload(t: Track) {
        downloadedFile(t.url)?.delete()
    }

    private fun videoId(url: String) =
        Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    private val fileRegex = Regex("^(.*) \\[([A-Za-z0-9_-]{11})]\\.(m4a|webm|opus|mp3|ogg|mp4|aac)$")

    private fun fileId(f: File) = fileRegex.find(f.name)?.groupValues?.get(2)

    private fun trackOf(f: File): Track? {
        val m = fileRegex.find(f.name) ?: return null
        val base = m.groupValues[1].trim()
        val artist = if (" - " in base) base.substringBefore(" - ") else ""
        val title = if (" - " in base) base.substringAfter(" - ") else base
        return Track("https://www.youtube.com/watch?v=${m.groupValues[2]}", title, artist, 0, null)
    }

    // --- Playlists ---
    fun playlists(): List<Playlist> {
        val text = runCatching { playlistsFile.readText() }.getOrDefault("[]")
        val arr = runCatching { JSONArray(text) }.getOrDefault(JSONArray())
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
        val tmp = File(playlistsFile.parentFile, "playlists.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(playlistsFile)
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
