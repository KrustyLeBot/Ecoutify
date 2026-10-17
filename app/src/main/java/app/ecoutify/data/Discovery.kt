package app.ecoutify.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Sources de recommandation : ListenBrainz (artistes similaires, sans clé ni compte)
 * et Last.fm (clé d'API gratuite facultative, réglages). Un jeton ListenBrainz, facultatif lui aussi,
 * sert uniquement à enregistrer tes écoutes sur ton profil.
 * Appels bloquants : à lancer hors du thread UI.
 */
class Discovery(private val http: OkHttpClient, private val store: Store) {

    /** Point de départ d'une recommandation : un artiste et un de ses titres (sert à retrouver son MBID). */
    data class Seed(val name: String, val title: String? = null)

    // ---------------------------------------------------------------- artistes similaires

    /** Artistes proches de [seed], via ListenBrainz puis Last.fm. */
    fun similarArtists(seed: Seed): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            val mbid = seed.title?.let { listenBrainzArtistMbid(seed.name, it) }
            if (mbid != null) out += listenBrainzSimilar(mbid)
        }
        if (store.lastFmKey.isNotBlank()) runCatching { out += lastFmSimilar(seed.name) }
        return out.toList()
    }

    private fun listenBrainzArtistMbid(artist: String, title: String): String? {
        val url = "https://api.listenbrainz.org/1/metadata/lookup/".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("recording_name", title)
            .build()
        val json = getJson(url) as? JSONObject ?: return null
        return json.optJSONArray("artist_mbids")?.optString(0)?.takeIf { it.isNotBlank() }
    }

    private fun listenBrainzSimilar(mbid: String): List<String> {
        val url = "https://labs.api.listenbrainz.org/similar-artists/json".toHttpUrl().newBuilder()
            .addQueryParameter("algorithm", LB_ALGORITHM)
            .addQueryParameter("artist_mbids", mbid)
            .build()
        val arr = getJson(url) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("artist_mbid") == mbid) null else o.optString("name").takeIf { it.isNotBlank() }
        }.take(25)
    }

    private fun lastFmSimilar(artist: String): List<String> {
        val url = lastFmUrl("artist.getsimilar").addQueryParameter("artist", artist).addQueryParameter("limit", "25").build()
        val arr = (getJson(url) as? JSONObject)?.optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }

    // ---------------------------------------------------------------- scrobbles ListenBrainz

    fun playingNow(t: Track) = submit("playing_now", t, null)

    fun scrobble(t: Track, startedAtSec: Long) = submit("single", t, startedAtSec)

    private fun submit(type: String, t: Track, listenedAt: Long?) {
        val token = store.lbToken.trim()
        if (token.isEmpty()) return
        val listen = JSONObject().put(
            "track_metadata",
            JSONObject().put("artist_name", t.artist).put("track_name", t.title)
                .put("additional_info", JSONObject().put("media_player", "Ecoutify").put("submission_client", "Ecoutify")),
        )
        if (listenedAt != null) listen.put("listened_at", listenedAt)
        val body = JSONObject().put("listen_type", type).put("payload", JSONArray().put(listen))
        val req = Request.Builder().url("https://api.listenbrainz.org/1/submit-listens")
            .header("Authorization", "Token $token").header("User-Agent", UA)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().close()
    }

    // ---------------------------------------------------------------- utilitaires

    private fun lastFmUrl(method: String): HttpUrl.Builder = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
        .addQueryParameter("method", method)
        .addQueryParameter("api_key", store.lastFmKey.trim())
        .addQueryParameter("format", "json")

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
        const val LB_ALGORITHM =
            "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    }
}
