package app.ecoutify.data

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Télécharge le flux audio d'origine (M4A/AAC ou Opus) pour l'écoute hors ligne.
 * Pas de conversion MP3 : on garde le fichier tel quel, sans perte de qualité et sans ffmpeg.
 */
class Downloads(
    private val catalog: Catalog,
    private val store: Store,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** url du morceau -> progression 0..100 (absent = pas en cours). */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val errors = MutableStateFlow<String?>(null)

    fun start(track: Track) {
        if (progress.value.containsKey(track.url) || store.downloadedFile(track.url) != null) return
        progress.update { it + (track.url to 0) }
        scope.launch {
            try {
                val (streamUrl, ext) = catalog.audioStream(track.url)
                val safe = track.url.substringAfter("v=").filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                val out = File(store.musicDir, "$safe.$ext")
                client.newCall(Request.Builder().url(streamUrl).header("User-Agent", OkHttpDownloader.USER_AGENT).build())
                    .execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        val body = resp.body ?: error("Réponse vide")
                        val total = body.contentLength().coerceAtLeast(1)
                        var done = 0L
                        var lastPct = -1
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
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
                store.addDownload(track, out)
            } catch (e: Exception) {
                errors.value = "Échec du téléchargement : ${e.message}"
            } finally {
                progress.update { it - track.url }
            }
        }
    }
}
