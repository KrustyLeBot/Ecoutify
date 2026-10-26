package app.ecoutify

import android.app.Application
import app.ecoutify.data.Catalog
import app.ecoutify.data.Covers
import app.ecoutify.data.DownloadNotifications
import app.ecoutify.data.Downloads
import app.ecoutify.data.OkHttpDownloader
import app.ecoutify.data.Recommender
import app.ecoutify.data.Store
import app.ecoutify.suite.SuiteUpdater
import app.ecoutify.recognize.SongRecognizer
import okhttp3.OkHttpClient

class EcoutifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
        downloads.schedulePeriodicSync()
    }

    val http by lazy { OkHttpClient.Builder().build() }
    val store by lazy { Store(this) }
    val catalog by lazy { Catalog(OkHttpDownloader(http)) }
    val covers by lazy { Covers(store, http) }
    val downloads by lazy { Downloads(this, catalog, store, http, covers) }
    val updater by lazy { SuiteUpdater(this, "KrustyLeBot/Ecoutify", "$packageName.updates") }
    val recommender by lazy { Recommender(catalog, store) }
    val recognizer by lazy { SongRecognizer(http) }
}
