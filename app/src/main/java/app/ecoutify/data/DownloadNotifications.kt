package app.ecoutify.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/** The ongoing notification shown while tracks are downloading in the background. */
object DownloadNotifications {
    private const val CHANNEL = "downloads"
    const val ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
    }

    /** [percent] null = indeterminate. */
    fun build(context: Context, text: String, percent: Int?): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ecoutify")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (percent == null) 0 else 100, percent ?: 0, percent == null)
            .build()

    fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID, notification)
        }

    fun update(context: Context, text: String, percent: Int?) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(ID, build(context, text, percent))
        }
    }
}
