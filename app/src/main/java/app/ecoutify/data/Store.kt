package app.ecoutify.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Historique d'écoute et morceaux téléchargés, stockés localement (aucun compte). */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("ecoutify", Context.MODE_PRIVATE)

    val musicDir: File
        get() = (context.getExternalFilesDir("music") ?: File(context.filesDir, "music")).also { it.mkdirs() }

    // --- Historique (alimente les recommandations) ---
    fun history(): List<Track> = readList("history")

    fun addToHistory(t: Track) {
        val list = (listOf(t) + history().filter { it.url != t.url }).take(60)
        writeList("history", list)
    }

    // --- Téléchargements ---
    fun downloads(): List<Track> = readList("downloads").filter { file(it) != null }

    fun downloadedFile(url: String): File? = readList("downloads").firstOrNull { it.url == url }?.let(::file)

    fun addDownload(t: Track, file: File) {
        prefs.edit().putString("file_${t.url}", file.name).apply()
        writeList("downloads", listOf(t) + readList("downloads").filter { it.url != t.url })
    }

    fun removeDownload(t: Track) {
        downloadedFile(t.url)?.delete()
        writeList("downloads", readList("downloads").filter { it.url != t.url })
    }

    private fun file(t: Track): File? =
        prefs.getString("file_${t.url}", null)?.let { File(musicDir, it) }?.takeIf { it.exists() }

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
