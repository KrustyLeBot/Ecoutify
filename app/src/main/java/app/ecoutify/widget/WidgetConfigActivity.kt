package app.ecoutify.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ecoutify.EcoutifyApp
import app.ecoutify.ui.EcoutifyTheme

/** Shown when a widget is placed: pick the playlist this widget will start. */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val store = (application as EcoutifyApp).store
        val playlists = store.playlists()
        setContent {
            EcoutifyTheme {
                Column(Modifier.systemBarsPadding().padding(20.dp)) {
                    Text("Playlist du widget", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (playlists.isEmpty()) "Crée d'abord une playlist dans Ecoutify." else "Un appui sur le widget la lance en aléatoire.",
                        Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn {
                        items(playlists, key = { it.id }) { p ->
                            Text(
                                p.name,
                                Modifier.fillMaxWidth().clickable {
                                    store.setWidgetPlaylist(widgetId, p.id)
                                    PlaylistWidgetProvider.update(this@WidgetConfigActivity, AppWidgetManager.getInstance(this@WidgetConfigActivity), widgetId)
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                }.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
