package app.ecoutify.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import app.ecoutify.EcoutifyApp
import app.ecoutify.R

/** One-tap home-screen widget: each instance is tied to a playlist and starts it in shuffle mode. */
class PlaylistWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = (context.applicationContext as EcoutifyApp).store
        appWidgetIds.forEach(store::removeWidget)
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val store = (context.applicationContext as EcoutifyApp).store
            val playlist = store.widgetPlaylist(widgetId)?.let { id -> store.playlists().firstOrNull { it.id == id } }
            val views = RemoteViews(context.packageName, R.layout.widget_playlist)
            views.setTextViewText(R.id.widget_label, playlist?.name ?: "?")
            val intent = PlayPlaylistActivity.intent(context, playlist?.id.orEmpty())
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("ecoutify://widget/$widgetId")) // makes each widget's PendingIntent distinct
            val pending = PendingIntent.getActivity(
                context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(widgetId, views)
        }

        /** Refreshes every placed widget (e.g. after a playlist was renamed or deleted). */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, PlaylistWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}
