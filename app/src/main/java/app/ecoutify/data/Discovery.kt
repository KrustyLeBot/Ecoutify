package app.ecoutify.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Artistes similaires via ListenBrainz (API publique, sans compte ni clé).
 * Appels bloquants : à lancer hors du thread UI.
 */
class Discovery(private val http: OkHttpClient) {

    /** Point de départ d'une recommandation : un artiste et un de ses titres (sert à retrouver son MBID). */
    data class Seed(val name: String, val title: String)

    fun similarArtists(seed: Seed): List<String> {
        val mbid = artistMbid(seed.name, seed.title) ?: return emptyList()
        val url = "https://labs.api.listenbrainz.org/similar-artists/json".toHttpUrl().newBuilder()
            .addQueryParameter("algorithm", ALGORITHM)
            .addQueryParameter("artist_mbids", mbid)
            .build()
        val arr = getJson(url) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("artist_mbid") == mbid) null else o.optString("name").takeIf { it.isNotBlank() }
        }.take(25)
    }

    private fun artistMbid(artist: String, title: String): String? {
        val url = "https://api.listenbrainz.org/1/metadata/lookup/".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("recording_name", title)
            .build()
        val json = getJson(url) as? JSONObject ?: return null
        return json.optJSONArray("artist_mbids")?.optString(0)?.takeIf { it.isNotBlank() }
    }

    private fun getJson(url: HttpUrl): Any? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (r.code != 200) return null
            val text = r.body?.string().orEmpty()
            if (text.isBlank()) return null
            return JSONTokener(text).nextValue()
        }
    }

    private companion object {
        const val UA = "Ecoutify/0.1 (https://github.com/KrustyLeBot/Ecoutify)"
        const val ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    }
}
