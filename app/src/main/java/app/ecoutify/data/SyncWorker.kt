package app.ecoutify.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ecoutify.EcoutifyApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Downloads, on an unmetered network, the tracks still missing from the playlists set to "keep offline". */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EcoutifyApp
        val missing = app.store.playlists().filter { it.keepOffline }.flatMap { it.tracks }
            .distinctBy { it.url }.filter { app.store.downloadedFile(it.url) == null }
        if (missing.isEmpty()) return Result.success()
        runCatching {
            setForeground(DownloadNotifications.foregroundInfo(DownloadNotifications.build(applicationContext, "Synchronisation des playlists", null)))
        }
        for ((index, track) in missing.withIndex()) {
            if (isStopped) return Result.retry()
            val label = "${index + 1}/${missing.size} · ${track.title}"
            try {
                withContext(Dispatchers.IO) {
                    app.downloads.downloadNow(track) { pct -> DownloadNotifications.update(applicationContext, label, pct) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return Result.retry() // connection lost: try again later, keeping what is already downloaded
            } catch (e: Exception) {
                // this track cannot be downloaded: skip it and continue with the others
            }
        }
        return Result.success()
    }
}
