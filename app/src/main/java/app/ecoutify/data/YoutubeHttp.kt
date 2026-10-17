package app.ecoutify.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicInteger

/** A resolved audio stream plus the exact way YouTube's servers accept requests for it. */
data class AudioSource(
    val url: String,
    val ext: String,
    val headers: Map<String, String>,
    /** true: NewPipe-style POST with `range` in the URL; false: plain GET with a Range header. */
    val post: Boolean,
)

/**
 * Builds requests for YouTube's "videoplayback" servers the way NewPipe does
 * (POST with a fixed body, `rn` and `range` URL parameters, client-specific headers);
 * without it streams are throttled or refused (HTTP 403).
 */
object YoutubeHttp {
    val POST_BODY = byteArrayOf(0x78, 0)
    private const val FORM = "application/x-www-form-urlencoded"
    private val requestNumber = AtomicInteger()

    /** User agent of the InnerTube client that produced [url] (the URL carries `&c=<CLIENT>`). */
    fun clientUserAgent(url: String): String = when {
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
        else -> OkHttpDownloader.USER_AGENT
    }

    fun headers(url: String, userAgent: String): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept-Encoding" to "identity",
            "TE" to "trailers",
            "Content-Type" to FORM,
        )
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            h["Origin"] = "https://www.youtube.com"
            h["Referer"] = "https://www.youtube.com"
            h["Sec-Fetch-Dest"] = "empty"
            h["Sec-Fetch-Mode"] = "cors"
            h["Sec-Fetch-Site"] = "cross-site"
        }
        return h
    }

    /** Adds `rn` and, if needed, the byte range `range=start-end` (end inclusive, null = to the end). */
    fun withParams(url: String, start: Long = 0, endInclusive: Long? = null): String {
        var u = url
        if (!u.contains("&rn=")) u += "&rn=${requestNumber.incrementAndGet()}"
        if (start > 0 || endInclusive != null) u += "&range=$start-${endInclusive ?: ""}"
        return u
    }

    /** OkHttp request for bytes [start]..[endInclusive] of [src] (null = to the end). */
    fun request(src: AudioSource, start: Long = 0, endInclusive: Long? = null): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
        src.headers.forEach { (k, v) -> builder.header(k, v) }
        if (src.post) {
            builder.url(withParams(src.url, start, endInclusive))
                .post(POST_BODY.toRequestBody(FORM.toMediaType()))
        } else {
            builder.url(src.url)
                .header("Range", "bytes=$start-${endInclusive ?: ""}")
        }
        return builder.build()
    }
}
