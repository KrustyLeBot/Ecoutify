package app.ecoutify.data

import org.json.JSONObject

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
            url = o.getString("url"),
            title = o.getString("title"),
            artist = o.optString("artist"),
            durationSec = o.optLong("duration"),
            thumbnail = if (o.isNull("thumb")) null else o.getString("thumb"),
        )
    }
}
