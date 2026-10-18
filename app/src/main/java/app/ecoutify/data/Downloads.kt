package app.ecoutify.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads the original audio stream (M4A/AAC or Opus) for offline listening.
 * No MP3 conversion: the file is kept as is, with no quality loss.
 */
class Downloads(
    private val catalog: Catalog,
    private val store: Store,
    private val client: OkHttpClient,
    private val covers: Covers,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Track URL -> progress 0..100 (absent = not downloading). */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Latest error message (consumed by the UI). */
    val errors = MutableStateFlow<String?>(null)

    fun start(track: Track) {
        if (progress.value.containsKey(track.url) || store.downloadedFile(track.url) != null) return
        progress.update { it + (track.url to 0) }
        scope.launch {
            var out: File? = null
            try {
                runCatching { covers.save(track) }
                val (streamUrl, ext) = catalog.audioStream(track.url)
                val finalFile = store.fileFor(track, ext)
                val partFile = File(finalFile.parentFile, finalFile.name + ".part")
                out = partFile
                val request = Request.Builder().url(streamUrl).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
                client.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val body = resp.body ?: error("Réponse vide")
                    val total = body.contentLength().coerceAtLeast(1)
                    var done = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        partFile.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress.update { it + (track.url to pct) }
                                }
                            }
                        }
                    }
                }
                check(partFile.renameTo(finalFile)) { "Impossible d'enregistrer le fichier" }
            } catch (e: Exception) {
                out?.delete()
                errors.value = "Téléchargement impossible (${track.title}) : ${e.message}"
            } finally {
                progress.update { it - track.url }
            }
        }
    }

}
