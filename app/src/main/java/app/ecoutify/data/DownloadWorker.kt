package app.ecoutify.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ecoutify.EcoutifyApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Downloads one track in the background. Network errors are retried a few times, other errors are reported. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EcoutifyApp
        val track = Track.fromJson(JSONObject(inputData.getString(KEY_TRACK) ?: return Result.success()))
        // Foreground status keeps the download alive when the app is closed; it may be refused while in the background.
        runCatching {
            setForeground(DownloadNotifications.foregroundInfo(DownloadNotifications.build(applicationContext, track.title, null)))
        }
        var lastNotified = -10
        return try {
            withContext(Dispatchers.IO) {
                app.downloads.downloadNow(track) { pct ->
                    app.downloads.progress.update { it + (track.url to pct) }
                    if (pct - lastNotified >= 5) {
                        lastNotified = pct
                        DownloadNotifications.update(applicationContext, track.title, pct)
                    }
                }
            }
            app.downloads.progress.update { it - track.url }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                app.downloads.fail(track, e)
                Result.success()
            }
        } catch (e: Exception) {
            app.downloads.fail(track, e)
            Result.success()
        }
    }

    companion object {
        const val KEY_TRACK = "track"
        private const val MAX_ATTEMPTS = 4
    }
}
