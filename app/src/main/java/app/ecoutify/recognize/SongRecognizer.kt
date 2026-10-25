package app.ecoutify.recognize

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * "What is playing?": records a few seconds from the microphone and asks Shazam, the way SongRec does
 * (unofficial endpoint used by Shazam's Android app). Only the fingerprint is sent, never the audio.
 */
class SongRecognizer(private val http: OkHttpClient) {
    data class Match(val title: String, val artist: String)

    /** Returns the song, or null when Shazam does not know it. Throws on network errors. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listen(onRecorded: () -> Unit = {}): Match? {
        val samples = withContext(Dispatchers.IO) { record() }
        onRecorded()
        return withContext(Dispatchers.Default) { Signature.uri(samples) }.let { uri ->
            withContext(Dispatchers.IO) { query(uri, samples.size) }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun record(): ShortArray {
        val rate = Signature.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, rate),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Micro indisponible")
        }
        val samples = ShortArray(rate * SECONDS)
        try {
            recorder.startRecording()
            var done = 0
            while (done < samples.size) {
                coroutineContext.ensureActive()
                val n = recorder.read(samples, done, minOf(rate / 10, samples.size - done))
                if (n < 0) throw IOException("Lecture du micro impossible ($n)")
                done += n
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return samples
    }

    private fun query(signatureUri: String, sampleCount: Int): Match? {
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2))
            .put(
                "signature", JSONObject()
                    .put("samplems", sampleCount * 1000L / Signature.SAMPLE_RATE)
                    .put("timestamp", now and 0xFFFFFFFFL)
                    .put("uri", signatureUri)
            )
            .put("timestamp", now and 0xFFFFFFFFL)
            .put("timezone", TimeZone.getDefault().id)
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val request = Request.Builder().url(url)
            .header("User-Agent", System.getProperty("http.agent") ?: "Dalvik/2.1.0 (Linux; U; Android)")
            .header("Content-Language", "en_US")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) throw IOException("Shazam refuse les requêtes pour l'instant, réessaie plus tard")
            if (!response.isSuccessful) throw IOException("Shazam a répondu ${response.code}")
            val track = JSONObject(response.body?.string().orEmpty()).optJSONObject("track") ?: return null
            val title = track.optString("title").trim()
            if (title.isEmpty()) return null
            return Match(title, track.optString("subtitle").trim())
        }
    }

    private companion object {
        const val SECONDS = 10
    }
}
