package app.ecoutify.data

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
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

    /**
     * Music similar to [url]: the YouTube Music "radio" of the track (a mix made of songs), falling back to
     * YouTube's "up next" list; either way non-music videos are filtered out and official audio comes first.
     */
    fun similar(url: String): List<Track> {
        val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1) ?: return emptyList()
        val radio = runCatching {
            PlaylistInfo.getInfo(youtube, "https://www.youtube.com/watch?v=$id&list=RDAMVM$id").relatedItems
        }.getOrDefault(emptyList())
        val items = radio.ifEmpty { StreamInfo.getInfo(youtube, url).relatedItems }
        return items.filterIsInstance<StreamInfoItem>()
            .filter { normalizeUrl(it.url) != normalizeUrl(url) && looksLikeMusic(it) }
            .sortedByDescending(::musicScore) // stable: keeps YouTube's order within a score
            .map(::toTrack)
    }

    private val notMusic = Regex(
        "podcast|interview|reaction|r[ée]action|trailer|tutorial|how to|review|vlog|news|gameplay|karaoke|" +
            "full album|compilation|playlist|live ?stream|episode|[0-9]+ ?(hours?|heures?)|megamix|non-?stop|" +
            "best of|top [0-9]+|explained|documentary|documentaire|behind the scenes|making of|teaser|asmr",
        RegexOption.IGNORE_CASE,
    )

    /** Songs are 1.5 to 8 minutes long; shorts, live streams, long mixes and talk content are dropped. */
    private fun looksLikeMusic(i: StreamInfoItem): Boolean =
        i.duration in 90..480 && !i.isShortFormContent && !notMusic.containsMatchIn(i.name)

    /** Auto-generated "- Topic" channels and VEVO are official music; verified channels are a weaker hint. */
    private fun musicScore(i: StreamInfoItem): Int {
        val uploader = i.uploaderName.orEmpty()
        return when {
            uploader.endsWith(" - Topic") -> 3
            uploader.endsWith("VEVO", ignoreCase = true) -> 2
            i.isUploaderVerified -> 1
            else -> 0
        }
    }

    /** The smallest thumbnail that is at least 300 px tall, else the largest one. */
    private fun bestThumbnail(images: List<Image>): String? {
        val sized = images.filter { it.height > 0 }
        return (sized.filter { it.height >= 300 }.minByOrNull { it.height }
            ?: sized.maxByOrNull { it.height }
            ?: images.lastOrNull())?.url
    }

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
        url = normalizeUrl(i.url),
        title = cleanTitle(i.name),
        artist = i.uploaderName.orEmpty().removeSuffix(" - Topic").removeSuffix("VEVO").trim(),
        durationSec = i.duration,
        thumbnail = bestThumbnail(i.thumbnails),
    )

    /** Strips "(Official Video)", "[Lyrics]"… from YouTube titles. */
    private fun cleanTitle(raw: String): String {
        val noise = Regex("\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|clip|visuali[sz]er|4k|hd)[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)
        return raw.replace(noise, "").trim().ifEmpty { raw }
    }
}
