package app.ecoutify.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Everything is local (no account).
 *
 * Music and playlists live in the public `Documents/Ecoutify/` folder, next to the other apps of the
 * suite (before, it was `Ecoutify/` at the root of the storage: [hasLegacyLibrary] / [importLegacyLibrary]
 * move it over). It survives uninstalling and everything in it is detected again after a
 * reinstall. This requires the "all files access" permission; until it is granted a private
 * folder is used, and its content is moved over once permission is given.
 *
 * - `music/<artist> - <title> [<YouTube id>].m4a`: one file per downloaded track;
 *   artist, title and id are read back from the file name.
 * - `covers/<YouTube id>.jpg`: cover images (so they are available offline and after a reinstall);
 * - `playlists.json`: the playlists.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("ecoutify", Context.MODE_PRIVATE)

    private val privateRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Ecoutify")

    private val publicRoot: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Ecoutify")

    /** Where the library lived before it moved into Documents: the root of the storage. */
    private val legacyRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "Ecoutify")

    /** Folder that Synchrotron syncs with Proton Drive (only meaningful with [hasStorageAccess]). */
    val syncPath: String get() = publicRoot.path

    /** Is access to the public `Ecoutify/` folder granted? */
    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private val root: File
        get() = (if (hasStorageAccess()) publicRoot else privateRoot).also { it.mkdirs() }

    val musicDir: File
        get() = File(root, "music").also { it.mkdirs() }

    private val coversDir: File
        get() = File(root, "covers")

    /** Where the cover of [url] is (or will be) saved; null if the URL has no video id. */
    fun coverFile(url: String): File? = videoId(url)?.let { File(coversDir, "$it.jpg") }

    private val playlistsFile: File
        get() = File(root, "playlists.json")

    /** Moves to the public folder whatever was downloaded before access was granted. */
    fun migratePrivateToPublic() {
        if (!hasStorageAccess() || !privateRoot.exists()) return
        runCatching {
            File(privateRoot, "music").listFiles()?.forEach { f ->
                val target = File(musicDir, f.name)
                if (!target.exists()) f.copyTo(target)
                f.delete()
            }
            File(privateRoot, "covers").listFiles()?.forEach { f ->
                val target = File(coversDir.also { it.mkdirs() }, f.name)
                if (!target.exists()) f.copyTo(target)
                f.delete()
            }
            val privatePlaylists = File(privateRoot, "playlists.json")
            if (privatePlaylists.exists() && !playlistsFile.exists()) privatePlaylists.copyTo(playlistsFile)
            privatePlaylists.delete()
        }
    }

    /** Is there a library in the old `Ecoutify/` folder at the root of the storage? */
    fun hasLegacyLibrary(): Boolean = hasStorageAccess() && legacyRoot.isDirectory &&
        legacyRoot.canonicalPath != publicRoot.canonicalPath &&
        (File(legacyRoot, "playlists.json").isFile || File(legacyRoot, "music").list().orEmpty().isNotEmpty() ||
            File(legacyRoot, "covers").list().orEmpty().isNotEmpty())

    val legacyPath: String get() = legacyRoot.path

    /**
     * Moves the old folder's music, covers and playlists into `Documents/Ecoutify`. Files already there are kept
     * (the old copy is left in place when it differs), playlists are merged by id and by track, and the old
     * folder is removed once empty. Returns the number of files moved.
     */
    fun importLegacyLibrary(): Int {
        if (!hasLegacyLibrary()) return 0
        var moved = 0
        fun move(from: File, to: File): Boolean {
            if (to.exists()) {
                if (to.length() == from.length()) { from.delete(); return false } // same file, already there
                return false
            }
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) { from.copyTo(to); from.delete() }
            return true
        }
        for (dir in listOf("music", "covers")) {
            File(legacyRoot, dir).listFiles()?.forEach { f ->
                if (f.isFile && !f.name.startsWith(".") && move(f, File(root, "$dir/${f.name}"))) moved++
            }
        }
        val old = File(legacyRoot, "playlists.json")
        if (old.isFile) {
            val legacy = runCatching { readPlaylists(old) }.getOrNull()
            if (legacy != null) {
                val current = playlists()
                val merged = current.map { c ->
                    val l = legacy.firstOrNull { it.id == c.id } ?: return@map c
                    c.copy(tracks = c.tracks + l.tracks.filter { t -> c.tracks.none { it.url == t.url } })
                } + legacy.filter { l -> current.none { it.id == l.id } }
                savePlaylists(merged)
                old.delete()
                moved++
            }
        }
        // Remove what is left only if it is empty: never delete anything unexpected.
        for (dir in listOf("music", "covers")) File(legacyRoot, dir).takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        legacyRoot.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        return moved
    }

    // --- History ---
    fun history(): List<Track> = readList("history")

    fun addToHistory(t: Track) {
        writeList("history", (listOf(t) + history().filter { it.url != t.url }).take(60))
    }

    // --- Last queue (restarted when a car / Bluetooth device asks to play and nothing is loaded) ---
    fun lastQueue(): List<Track> = readList("last_queue")
    fun saveLastQueue(list: List<Track>) = writeList("last_queue", list.take(300))

    // --- Recommendations (kept for offline mode) ---
    fun cachedRecommendations(): List<Track> = readList("recs")
    fun saveRecommendations(list: List<Track>) = writeList("recs", list)

    // --- Downloads (re-read from the folder each time) ---
    fun downloads(): List<Track> =
        (musicDir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.mapNotNull { trackOf(it) }

    fun downloadedFile(url: String): File? {
        val id = videoId(url) ?: return null
        return (musicDir.listFiles() ?: emptyArray()).firstOrNull { fileId(it) == id }
    }

    /** Destination file for [t] (written as a `.synctmp-` temporary first, renamed once complete). */
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
    /** An unreadable playlists.json reads as empty here, but [savePlaylists] refuses to overwrite it. */
    fun playlists(): List<Playlist> = runCatching { readPlaylists(playlistsFile) }.getOrNull() ?: emptyList()

    private fun readPlaylists(file: File): List<Playlist> {
        val arr = AtomicFiles.read(file) { JSONArray(it) } ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val tracks = o.getJSONArray("tracks")
            Playlist(
                o.getString("id"),
                o.getString("name"),
                (0 until tracks.length()).map { i -> Track.fromJson(tracks.getJSONObject(i)) },
                o.optBoolean("keepOffline", false),
            )
        }
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("keepOffline", p.keepOffline)
                    .put("tracks", JSONArray().also { a -> p.tracks.forEach { a.put(it.toJson()) } })
            )
        }
        AtomicFiles.read(playlistsFile) { JSONArray(it) } // throws when the current file is unreadable
        AtomicFiles.write(playlistsFile, arr.toString())
    }

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), emptyList())
        savePlaylists(playlists() + p)
        return p
    }

    /** Creates a playlist that already holds [tracks] (used by the playlist import). */
    fun createPlaylistWith(name: String, tracks: List<Track>): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), tracks.distinctBy { it.url })
        savePlaylists(playlists() + p)
        return p
    }

    fun setKeepOffline(id: String, on: Boolean) = savePlaylists(playlists().map {
        if (it.id == id) it.copy(keepOffline = on) else it
    })

    fun renamePlaylist(id: String, name: String) = savePlaylists(playlists().map {
        if (it.id == id) it.copy(name = name.trim()) else it
    })

    fun deletePlaylist(id: String) = savePlaylists(playlists().filter { it.id != id })

    fun addToPlaylist(id: String, t: Track) = savePlaylists(playlists().map {
        if (it.id == id && it.tracks.none { x -> x.url == t.url }) it.copy(tracks = it.tracks + t) else it
    })

    fun removeFromPlaylist(id: String, t: Track) = savePlaylists(playlists().map {
        if (it.id == id) it.copy(tracks = it.tracks.filter { x -> x.url != t.url }) else it
    })

    // --- Home-screen widgets (widget id -> playlist id) ---
    fun widgetPlaylist(widgetId: Int): String? = prefs.getString("widget_$widgetId", null)
    fun setWidgetPlaylist(widgetId: Int, playlistId: String) = prefs.edit().putString("widget_$widgetId", playlistId).apply()
    fun removeWidget(widgetId: Int) = prefs.edit().remove("widget_$widgetId").apply()

    // --- Helpers ---
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
