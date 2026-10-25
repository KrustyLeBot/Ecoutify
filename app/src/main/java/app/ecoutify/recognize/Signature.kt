package app.ecoutify.recognize

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Shazam audio fingerprint, ported from SongRec (github.com/marin-m/SongRec, GPL-3.0,
 * src/core/fingerprinting/algorithm.rs and signature_format.rs).
 *
 * Input: mono 16 kHz signed 16-bit samples. Output: the `data:audio/vnd.shazam.sig;base64,...` URI
 * Shazam's servers expect. Pure Kotlin, no Android dependency.
 */
object Signature {
    const val SAMPLE_RATE = 16_000

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    fun uri(samples: ShortArray): String =
        "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode(samples.size, peaks(samples)))

    // ---- fingerprinting (algorithm.rs) ----

    private val hanning = FloatArray(2048) { n -> (0.5 * (1 - cos(2 * PI * (n + 1) / 2049))).toFloat() }

    private fun peaks(samples: ShortArray): Array<MutableList<Peak>> {
        val bands = Array(4) { mutableListOf<Peak>() }
        val ring = ShortArray(2048)
        var ringIndex = 0
        val windowed = FloatArray(2048)
        val fftOutputs = Array(256) { FloatArray(1025) }
        var fftIndex = 0
        val spreadOutputs = Array(256) { FloatArray(1025) }
        var spreadIndex = 0
        var spreadDone = 0
        val fft = Fft2048()

        var offset = 0
        while (offset + 128 <= samples.size) {
            // FFT of the last 2048 samples, every 128 new samples
            System.arraycopy(samples, offset, ring, ringIndex, 128)
            offset += 128
            ringIndex = (ringIndex + 128) and 2047
            for (i in 0 until 2048) windowed[i] = ring[(i + ringIndex) and 2047] * hanning[i]
            fft.powerSpectrum(windowed, fftOutputs[fftIndex])
            fftIndex = (fftIndex + 1) and 255

            // Peak spreading, in frequency then in time
            val spread = spreadOutputs[spreadIndex]
            System.arraycopy(fftOutputs[(fftIndex - 1) and 255], 0, spread, 0, 1025)
            for (p in 0..1022) spread[p] = max(max(spread[p], spread[p + 1]), spread[p + 2])
            for (p in 0..1024) {
                for (former in intArrayOf(1, 3, 6)) {
                    val f = spreadOutputs[(spreadIndex - former) and 255]
                    f[p] = max(f[p], spread[p])
                }
            }
            spreadIndex = (spreadIndex + 1) and 255

            spreadDone++
            if (spreadDone >= 46) recognizePeaks(fftOutputs, fftIndex, spreadOutputs, spreadIndex, spreadDone, bands)
        }
        return bands
    }

    private val neighbours = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val otherFfts = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    private fun magnitude(x: Float) = max(ln(x), 1f / 64f) * 1477.3f + 6144f

    private fun recognizePeaks(
        fftOutputs: Array<FloatArray>, fftIndex: Int, spreadOutputs: Array<FloatArray>, spreadIndex: Int,
        spreadDone: Int, bands: Array<MutableList<Peak>>,
    ) {
        val fft46 = fftOutputs[(fftIndex - 46) and 255]
        val spread49 = spreadOutputs[(spreadIndex - 49) and 255]
        for (bin in 10..1014) {
            val v = fft46[bin]
            if (v < 1f / 64f || v < spread49[bin - 1]) continue
            var maxNeighbour = 0f
            for (n in neighbours) maxNeighbour = max(maxNeighbour, spread49[bin + n])
            if (v <= maxNeighbour) continue
            var maxOther = maxNeighbour
            for (o in otherFfts) maxOther = max(maxOther, spreadOutputs[(spreadIndex + o) and 255][bin - 1])
            if (v <= maxOther) continue

            val m = magnitude(v)
            val before = magnitude(fft46[bin - 1])
            val after = magnitude(fft46[bin + 1])
            val variation1 = m * 2 - before - after
            val variation2 = (after - before) * 32 / variation1
            val correctedBin = (bin * 64 + variation2.toInt()) and 0xFFFF
            val hz = (correctedBin * (16000f / 2f / 1024f / 64f)).toInt()
            val band = when (hz) {
                in 250..519 -> 0
                in 520..1449 -> 1
                in 1450..3499 -> 2
                in 3500..5500 -> 3
                else -> continue
            }
            bands[band] += Peak(spreadDone - 46, m.toInt().coerceIn(0, 0xFFFF), correctedBin)
        }
    }

    // ---- binary format (signature_format.rs) ----

    private fun encode(numberSamples: Int, bands: Array<MutableList<Peak>>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        bands.forEachIndexed { band, peaks ->
            if (peaks.isEmpty()) return@forEachIndexed
            val p = java.io.ByteArrayOutputStream()
            var pass = 0
            for (peak in peaks) {
                if (peak.pass - pass >= 255) {
                    p.write(0xff)
                    p.write(le32(peak.pass))
                    pass = peak.pass
                }
                p.write(peak.pass - pass)
                p.write(le16(peak.magnitude))
                p.write(le16(peak.bin))
                pass = peak.pass
            }
            val bytes = p.toByteArray()
            body.write(le32(0x60030040 + band))
            body.write(le32(bytes.size))
            body.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { body.write(0) }
        }
        val content = body.toByteArray()
        val size = 8 + content.size // "size minus header" counts the 8-byte block below plus the bands
        val buf = ByteBuffer.allocate(48 + size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xcafe2580.toInt()).putInt(0).putInt(size).putInt(0x94119c00.toInt())
        buf.putInt(0).putInt(0).putInt(0)
        buf.putInt(3 shl 27) // 16 kHz
        buf.putInt(0).putInt(0)
        buf.putInt(numberSamples + (SAMPLE_RATE * 0.24f).toInt())
        buf.putInt((15 shl 19) + 0x40000)
        buf.putInt(0x40000000).putInt(size)
        buf.put(content)
        val out = buf.array()
        val crc = CRC32().apply { update(out, 8, out.size - 8) }.value.toInt()
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc)
        return out
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}

/** In-place radix-2 FFT of 2048 real samples; writes |X[k]|² / 2^17 (floored at 1e-10) for k = 0..1024. */
private class Fft2048 {
    private val n = 2048
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { -sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - 11) }

    fun powerSpectrum(input: FloatArray, out: FloatArray) {
        for (i in 0 until n) { re[rev[i]] = input[i]; im[rev[i]] = 0f }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        for (k in 0..1024) out[k] = max((re[k] * re[k] + im[k] * im[k]) / (1 shl 17), 0.0000000001f)
    }
}
