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
import java.util.concurrent.ConcurrentHashMap

/** Access to the YouTube / YouTube Music catalog through NewPipeExtractor. Blocking calls: run off the UI thread. */
class Catalog(downloader: OkHttpDownloader) {

    init {
        NewPipe.init(downloader, Localization("fr", "FR"), ContentCountry("FR"))
    }

    private val youtube = ServiceList.YouTube
    private val urlCache = ConcurrentHashMap<String, Pair<Long, Pair<String, String>>>()

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

    /** Direct URL of the best audio stream (M4A preferred), cached ~2 h (URLs expire). */
    fun audioUrl(watchUrl: String): String = audioStream(watchUrl).first

    /** (url, extension) of the best audio stream. */
    fun audioStream(watchUrl: String): Pair<String, String> {
        urlCache[watchUrl]?.let { (expires, value) -> if (expires > System.currentTimeMillis()) return value }
        val info = StreamInfo.getInfo(youtube, watchUrl)
        val candidates = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = candidates.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: candidates.maxByOrNull { it.averageBitrate }
            ?: error("Aucun flux audio disponible")
        val value = best.content to (best.format?.suffix ?: "m4a")
        urlCache[watchUrl] = System.currentTimeMillis() + 2 * 3600_000L to value
        return value
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
