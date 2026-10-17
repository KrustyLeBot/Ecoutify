package app.ecoutify.data

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mimics the way NewPipe queries YouTube's "videoplayback" servers
 * (POST request with a fixed body, `rn` and `range` parameters in the URL, browser headers);
 * without it streams are throttled or refused.
 */
object YoutubeHttp {
    val POST_BODY = byteArrayOf(0x78, 0)
    private val requestNumber = AtomicInteger()

    fun headers(url: String): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to OkHttpDownloader.USER_AGENT,
            "Accept-Encoding" to "identity",
            "TE" to "trailers",
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

    fun request(url: String, start: Long = 0, endInclusive: Long? = null): okhttp3.Request =
        okhttp3.Request.Builder()
            .url(withParams(url, start, endInclusive))
            .post(POST_BODY.toRequestBody())
            .apply { headers(url).forEach { (k, v) -> header(k, v) } }
            .build()
}
