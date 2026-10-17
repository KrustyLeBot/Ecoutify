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

/** Accès au catalogue YouTube / YouTube Music via NewPipeExtractor. Appels bloquants : à lancer hors du thread UI. */
class Catalog(downloader: OkHttpDownloader) {

    init {
        NewPipe.init(downloader, Localization("fr", "FR"), ContentCountry("FR"))
    }

    private val youtube = ServiceList.YouTube
    private val urlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Recherche limitée aux morceaux (filtre « YouTube Music – Titres »). */
    fun search(query: String): List<Track> {
        val extractor = youtube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""
        )
        extractor.fetchPage()
        return extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toTrack)
    }

    /** Titres similaires (colonne « À suivre » de YouTube) : base de la recommandation et de l'autoplay. */
    fun related(url: String): List<Track> =
        StreamInfo.getInfo(youtube, url).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.duration in 60..900 } // écarte shorts, mixes de 3 h, etc.
            .map(::toTrack)

    /** URL directe du meilleur flux audio (M4A de préférence), mise en cache ~2 h (les URL expirent). */
    fun audioUrl(watchUrl: String): String = audioStream(watchUrl).first

    /** (url, extension) du meilleur flux audio. */
    fun audioStream(watchUrl: String): Pair<String, String> {
        urlCache[watchUrl]?.let { (expires, value) ->
            if (expires > System.currentTimeMillis()) return value.substringBefore('|') to value.substringAfter('|')
        }
        val info = StreamInfo.getInfo(youtube, watchUrl)
        val candidates = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val best = candidates.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: candidates.maxByOrNull { it.averageBitrate }
            ?: error("Aucun flux audio disponible")
        val ext = best.format?.suffix ?: "m4a"
        urlCache[watchUrl] = System.currentTimeMillis() + 2 * 3600_000L to "${best.content}|$ext"
        return best.content to ext
    }

    private fun toTrack(i: StreamInfoItem) = Track(
        url = i.url,
        title = cleanTitle(i.name),
        artist = i.uploaderName.orEmpty().removeSuffix(" - Topic").removeSuffix("VEVO").trim(),
        durationSec = i.duration,
        thumbnail = i.thumbnails.firstOrNull()?.url,
    )

    /** Retire les mentions « (Official Video) », « [Lyrics] »… des titres YouTube. */
    private fun cleanTitle(raw: String): String {
        val noise = Regex("\\s*[\\(\\[][^\\)\\]]*(official|video|audio|lyrics?|clip|visuali[sz]er|4k|hd)[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)
        return raw.replace(noise, "").trim().ifEmpty { raw }
    }
}
