package app.ecoutify.data

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Access to the YouTube / YouTube Music catalog through NewPipeExtractor. Blocking calls: run off the UI thread. */
class Catalog(downloader: OkHttpDownloader, private val http: OkHttpClient) {

    init {
        NewPipe.init(downloader, Localization("fr", "FR"), ContentCountry("FR"))
    }

    private val youtube = ServiceList.YouTube
    private val sourceCache = ConcurrentHashMap<String, Pair<Long, AudioSource>>()

    /** Search restricted to songs (the "YouTube Music – Songs" filter). */
    fun search(query: String): List<Track> {
        val extractor = youtube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
        )
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toTrack)
    }

    /** Similar tracks (YouTube's "up next" column): the basis of recommendations. */
    fun related(url: String): List<Track> =
        StreamInfo.getInfo(youtube, url).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 60..900 } // drops shorts, 3-hour mixes, etc.
            .map(::toTrack)

    /** Direct URL of the best working audio stream. */
    fun audioUrl(watchUrl: String): String = audioSource(watchUrl).url

    /**
     * Best audio stream that YouTube actually serves, cached ~2 h (URLs expire).
     * Each candidate is probed with a tiny ranged request, trying the request styles of the
     * different YouTube clients, until one answers 2xx.
     */
    fun audioSource(watchUrl: String): AudioSource {
        sourceCache[watchUrl]?.let { (expires, source) -> if (expires > System.currentTimeMillis()) return source }
        val attempts = mutableListOf<String>()

        fun accept(source: AudioSource, label: String): Boolean {
            val code = probe(source)
            attempts += "$label:$code"
            if (code !in 200..299) return false
            sourceCache[watchUrl] = System.currentTimeMillis() + 2 * 3600_000L to source
            return true
        }

        val info = StreamInfo.getInfo(youtube, watchUrl)
        val candidates = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
            .take(3)

        // 1) The very first approach, which worked: plain GET with a browser user agent and nothing else.
        candidates.firstOrNull()?.let { c ->
            val source = AudioSource(c.content, c.format?.suffix ?: "m4a", mapOf("User-Agent" to OkHttpDownloader.USER_AGENT), false)
            if (accept(source, "plain")) return source
        }

        // 2) Android VR InnerTube client: serves plain audio URLs without a PoToken.
        runCatching { vrCandidate(watchUrl) }.getOrNull()?.let { (url, ext) ->
            for (post in listOf(false, true)) {
                val source = AudioSource(url, ext, mapOf("User-Agent" to VR_USER_AGENT, "Accept-Encoding" to "identity"), post)
                if (accept(source, "vr-${if (post) "post" else "get"}")) return source
            }
        }

        // 3) NewPipe-style requests, with the request styles of the different clients.
        for (c in candidates) {
            val url = c.content
            val ext = c.format?.suffix ?: "m4a"
            val agents = listOf(YoutubeHttp.clientUserAgent(url), OkHttpDownloader.USER_AGENT).distinct()
            for (post in listOf(false, true)) for (agent in agents) {
                val source = AudioSource(url, ext, YoutubeHttp.headers(url, agent), post)
                if (accept(source, "np${c.itagItem?.id}-${if (post) "post" else "get"}")) return source
            }
        }
        error("YouTube a refusé le flux (${attempts.joinToString(" ")})")
    }

    /** Best audio/mp4 stream URL from the Android VR client's player response, or null. */
    private fun vrCandidate(watchUrl: String): Pair<String, String>? {
        val videoId = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(watchUrl)?.groupValues?.get(1) ?: return null
        val client = JSONObject()
            .put("clientName", "ANDROID_VR").put("clientVersion", VR_VERSION)
            .put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("androidSdkVersion", 32)
            .put("userAgent", VR_USER_AGENT).put("osName", "Android").put("osVersion", "12L")
            .put("hl", "en").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
        val body = JSONObject().put("context", JSONObject().put("client", client))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", VR_USER_AGENT)
            .header("X-YouTube-Client-Name", "28")
            .header("X-YouTube-Client-Version", VR_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val formats = JSONObject(r.body?.string().orEmpty())
                .optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
            var best: JSONObject? = null
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                if (!f.optString("mimeType").startsWith("audio/mp4") || f.optString("url").isEmpty()) continue
                if (best == null || f.optInt("bitrate") > best.optInt("bitrate")) best = f
            }
            return best?.let { it.getString("url") to "m4a" }
        }
    }

    private fun probe(source: AudioSource): Int = try {
        http.newCall(YoutubeHttp.request(source, 0, 1)).execute().use { it.code }
    } catch (e: IOException) {
        -1
    }

    private fun toTrack(i: StreamInfoItem) = Track(
        url = i.url,
        title = cleanTitle(i.name),
        artist = i.uploaderName.orEmpty().removeSuffix(" - Topic").removeSuffix("VEVO").trim(),
        durationSec = i.duration,
        thumbnail = i.thumbnails.firstOrNull()?.url,
    )

    /** Strips "(Official Video)", "[Lyrics]"… from YouTube titles. */
    private fun cleanTitle(raw: String): String {
        val noise = Regex("\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|clip|visuali[sz]er|4k|hd)[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)
        return raw.replace(noise, "").trim().ifEmpty { raw }
    }

    private companion object {
        const val VR_VERSION = "1.60.19"
        const val VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/$VR_VERSION (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    }
}
