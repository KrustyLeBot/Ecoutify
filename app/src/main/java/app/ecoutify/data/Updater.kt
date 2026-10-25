package app.ecoutify.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * In-app updates from the `latest` GitHub release (published by the CI with the APK as a plain asset).
 * The APK is downloaded to the app cache and removed once the installed build has caught up with it.
 * Blocking calls: run off the UI thread.
 */
class Updater(private val context: Context, private val client: OkHttpClient) {

    /** [build] is the CI run number, which is also the app's version code. */
    data class Release(val build: Int, val url: String, val sizeBytes: Long, val sha256: String = "")

    private val dir: File get() = File(context.cacheDir, "updates")

    fun currentBuild(): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    /** The newest published build, or null if there is none. */
    fun latest(): Release? {
        val request = Request.Builder().url(API).header("Accept", "application/vnd.github+json")
            .header("User-Agent", OkHttpDownloader.USER_AGENT).build()
        client.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val json = JSONObject(r.body?.string().orEmpty())
            val build = Regex("(\\d+)\\s*$").find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    // GitHub publishes the asset's SHA-256 as "sha256:<hex>"
                    val digest = asset.optString("digest").removePrefix("sha256:").takeIf { asset.optString("digest").startsWith("sha256:") }.orEmpty()
                    return Release(build, asset.getString("browser_download_url"), asset.optLong("size"), digest.lowercase())
                }
            }
            return null
        }
    }

    /** Downloads [release] into the app cache (replacing any earlier download) and returns the file. */
    fun download(release: Release, onProgress: (Int) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update-${release.build}.apk")
        val part = File(dir, "update-${release.build}.apk.part")
        val request = Request.Builder().url(release.url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
        client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body ?: error("Réponse vide")
            val total = (body.contentLength().takeIf { it > 0 } ?: release.sizeBytes).coerceAtLeast(1)
            var done = 0L
            var last = -1
            val sha = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt().coerceAtMost(100)
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        if (release.sizeBytes > 0 && part.length() != release.sizeBytes) {
            part.delete()
            error("Téléchargement incomplet")
        }
        if (release.sha256.isNotEmpty() && sha.digest().joinToString("") { "%02x".format(it) } != release.sha256) {
            part.delete()
            error("Le fichier téléchargé ne correspond pas à l'empreinte SHA-256 publiée")
        }
        check(part.renameTo(target)) { "Impossible d'enregistrer la mise à jour" }
        return target
    }

    /** Opens Android's installer on [file]; the user confirms there. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Deletes downloaded updates that are no longer needed: those whose build is not newer than the installed one
     * (the update went through). A download still waiting to be installed is kept. Called at every app start.
     */
    fun cleanup() {
        val current = currentBuild()
        dir.listFiles()?.forEach { f ->
            val build = Regex("update-(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (build == null || build <= current) f.delete()
        }
    }

    private companion object {
        const val API = "https://api.github.com/repos/KrustyLeBot/Ecoutify/releases/tags/latest"
    }
}
