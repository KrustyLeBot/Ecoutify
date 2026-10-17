package app.ecoutify.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Télécharge le flux audio d'origine (M4A/AAC ou Opus) pour l'écoute hors ligne.
 * Pas de conversion MP3 : on garde le fichier tel quel, sans perte de qualité.
 */
class Downloads(
    private val catalog: Catalog,
    private val store: Store,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** url du morceau -> progression 0..100 (absent = pas en cours). */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Dernier message d'erreur (consommé par l'UI). */
    val errors = MutableStateFlow<String?>(null)

    fun start(track: Track) {
        if (progress.value.containsKey(track.url) || store.downloadedFile(track.url) != null) return
        progress.update { it + (track.url to 0) }
        scope.launch {
            var out: File? = null
            try {
                val (streamUrl, ext) = catalog.audioStream(track.url)
                val safe = track.url.substringAfter("v=").filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                out = File(store.musicDir, "$safe.$ext")
                val total = Regex("[?&]clen=(\\d+)").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()
                out.outputStream().use { output ->
                    var done = 0L
                    var lastPct = -1
                    while (total == null || done < total) {
                        val end = if (total != null) minOf(done + CHUNK, total) - 1 else null
                        client.newCall(YoutubeHttp.request(streamUrl, done, end)).execute().use { resp ->
                            check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            val body = resp.body ?: error("Réponse vide")
                            val buf = ByteArray(64 * 1024)
                            body.byteStream().use { input ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    val pct = if (total != null) (done * 100 / total).toInt() else 50
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        progress.update { it + (track.url to pct) }
                                    }
                                }
                            }
                        }
                        if (total == null) break // taille inconnue : une seule requête
                    }
                }
                store.addDownload(track, out)
            } catch (e: Exception) {
                out?.delete()
                errors.value = "Téléchargement impossible (${track.title}) : ${e.message}"
            } finally {
                progress.update { it - track.url }
            }
        }
    }

    private companion object {
        const val CHUNK = 8L * 1024 * 1024
    }
}
