package app.ecoutify

import android.app.Application
import app.ecoutify.data.Catalog
import app.ecoutify.data.Discovery
import app.ecoutify.data.Downloads
import app.ecoutify.data.OkHttpDownloader
import app.ecoutify.data.Recommender
import app.ecoutify.data.Store
import okhttp3.OkHttpClient

class EcoutifyApp : Application() {
    val http by lazy { OkHttpClient.Builder().build() }
    val store by lazy { Store(this) }
    val catalog by lazy { Catalog(OkHttpDownloader(http)) }
    val downloads by lazy { Downloads(catalog, store, http) }
    val discovery by lazy { Discovery(http) }
    val recommender by lazy { Recommender(catalog, store, discovery) }
}
