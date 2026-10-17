package app.ecoutify.data

import org.json.JSONObject

/** Canonical watch URL (YouTube Music links use another host), so the same video always has the same URL. */
fun normalizeUrl(url: String): String =
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.let { "https://www.youtube.com/watch?v=${it.groupValues[1]}" } ?: url

data class Track(
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnail: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url).put("title", title).put("artist", artist)
        .put("duration", durationSec).put("thumb", thumbnail ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            url = normalizeUrl(o.getString("url")),
            title = o.getString("title"),
            artist = o.optString("artist"),
            durationSec = o.optLong("duration"),
            thumbnail = if (o.isNull("thumb")) null else o.getString("thumb"),
        )
    }
}
