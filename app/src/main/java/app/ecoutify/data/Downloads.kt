package app.ecoutify.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the original audio stream (M4A/AAC or Opus) for offline listening.
 * No MP3 conversion: the file is kept as is, with no quality loss.
 *
 * Downloads run in WorkManager (see [DownloadWorker] and [SyncWorker]): they continue when the app is closed,
 * show a progress notification and are retried when the connection drops.
 */
class Downloads(
    private val context: Context,
    private val catalog: Catalog,
    private val store: Store,
    private val client: OkHttpClient,
    private val covers: Covers,
) {
    /** Track URL -> progress 0..100 for the downloads running right now. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Latest error message (consumed by the UI). */
    val errors = MutableStateFlow<String?>(null)

    private val work get() = WorkManager.getInstance(context)

    /** Queues [track] for download (one at a time, in the order requested). */
    fun start(track: Track) {
        if (store.downloadedFile(track.url) != null || track.url in progress.value) return
        progress.update { it + (track.url to 0) }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_TRACK to track.toJson().toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(TAG_PREFIX + track.url)
            .build()
        work.enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Downloads, on an unmetered network, every track of the playlists set to "keep offline". */
    fun scheduleSync() {
        if (store.playlists().none { it.keepOffline }) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(syncConstraints).build()
        work.enqueueUniqueWork(SYNC, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Checks the "keep offline" playlists every few hours as well. */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(syncConstraints).build()
        work.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Downloads [track] now (blocking). Throws on failure; a partial file is removed. */
    fun downloadNow(track: Track, onProgress: (Int) -> Unit) {
        if (store.downloadedFile(track.url) != null) return
        runCatching { covers.save(track) }
        val (streamUrl, ext) = catalog.audioStream(track.url)
        val finalFile = store.fileFor(track, ext)
        val partFile = File(finalFile.parentFile, finalFile.name + ".part")
        try {
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
                            val pct = (done * 100 / total).toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            check(partFile.renameTo(finalFile)) { "Impossible d'enregistrer le fichier" }
        } catch (e: Throwable) {
            partFile.delete()
            throw e
        }
    }

    /** Gives up on [track]: forgets it and tells the UI. */
    fun fail(track: Track, error: Throwable) {
        progress.update { it - track.url }
        errors.value = "Téléchargement impossible (${track.title}) : ${error.message}"
    }

    companion object {
        const val QUEUE = "downloads"
        const val TAG_PREFIX = "track:"
        private const val SYNC = "auto-sync"
        private const val SYNC_PERIODIC = "auto-sync-periodic"
        private val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
