package app.ecoutify.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Saves cover images into `Ecoutify/covers/` so they show offline and survive a reinstall. Blocking calls. */
class Covers(private val store: Store, private val client: OkHttpClient) {

    /** Incremented each time a cover file is written, so the UI can refresh. */
    val version = MutableStateFlow(0)

    /** Saves the cover of [track] unless it is already there. Tries the track's own image, then YouTube's video thumbnails. */
    fun save(track: Track) {
        val target = store.coverFile(track.url) ?: return
        if (target.exists()) return
        val id = target.nameWithoutExtension
        val sources = listOfNotNull(
            track.thumbnail,
            "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$id/mqdefault.jpg",
        )
        for (url in sources) {
            val bytes = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            } catch (e: IOException) {
                null
            }
            if (bytes == null || bytes.size < 1024) continue
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeBytes(bytes)
            if (part.renameTo(target)) {
                version.update { it + 1 }
                return
            }
        }
    }

    /** Fetches the covers that are missing for [tracks]. */
    fun ensure(tracks: List<Track>) {
        tracks.distinctBy { it.url }.forEach { save(it) }
    }

    /** What the UI should load for [track]: the saved file, else the track's image, else the video thumbnail. */
    fun model(track: Track): String? {
        val file = store.coverFile(track.url)
        if (file != null && file.exists()) return "file://" + file.absolutePath
        return track.thumbnail
            ?: file?.nameWithoutExtension?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
    }
}
