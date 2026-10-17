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
import okhttp3.OkHttpClient
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
        val info = StreamInfo.getInfo(youtube, watchUrl)
        val candidates = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
            .take(4)
        if (candidates.isEmpty()) error("Aucun flux audio disponible")

        var lastCode = 0
        for (c in candidates) {
            val url = c.content
            val ext = c.format?.suffix ?: "m4a"
            val agents = listOf(YoutubeHttp.clientUserAgent(url), OkHttpDownloader.USER_AGENT).distinct()
            for (post in listOf(true, false)) for (agent in agents) {
                val source = AudioSource(url, ext, YoutubeHttp.headers(url, agent), post)
                val code = probe(source)
                if (code in 200..299) {
                    sourceCache[watchUrl] = System.currentTimeMillis() + 2 * 3600_000L to source
                    return source
                }
                lastCode = code
            }
        }
        error("Flux refusé par YouTube (HTTP $lastCode)")
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
}
