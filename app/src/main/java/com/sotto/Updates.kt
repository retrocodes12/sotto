package com.sotto

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub releases of this repository: check, download into the
 * cache, hand the APK to the system installer. Only assets hosted under our own release
 * page are ever installed. Builds share one signing key, so updates install over the top.
 */
object Updates {
    private const val REPO = "retrocodes12/sotto"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET_PREFIX = "https://github.com/$REPO/releases/download/"
    const val EVERGREEN_APK = "https://github.com/$REPO/releases/latest/download/sotto.apk"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_FEED_BYTES = 256 * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class Release(val version: String, val notes: String, val apkUrl: String, val bytes: Long)

    /** The newest release, or null when offline or the feed is unreadable. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject(getText(LATEST_API))
            val version = cleanVersion(j.optString("tag_name"))
            if (version.isEmpty()) return@withContext null
            var apk = EVERGREEN_APK
            var bytes = 0L
            val assets = j.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val u = a.optString("browser_download_url")
                if (u.startsWith(ASSET_PREFIX) && u.endsWith(".apk")) { apk = u; bytes = a.optLong("size"); break }
            }
            val notes = j.optString("body").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(160).orEmpty()
            Release(version, notes, apk, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** "v0.10" -> "0.10"; anything that is not dotted digits is rejected as empty. */
    fun cleanVersion(raw: String): String {
        val v = raw.trim().removePrefix("v").removePrefix("V").trim()
        return if (Regex("^\\d+(\\.\\d+){1,3}$").matches(v)) v else ""
    }

    /** Strict "remote is newer than current" over dotted numeric versions. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = cleanVersion(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun getText(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
        conn.setRequestProperty("User-Agent", "Sotto/${BuildConfig.VERSION_NAME}")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // A release feed is a few kilobytes. Read a bounded amount so a wrong or hostile
            // answer cannot pull the phone's memory out from under it.
            val body = stream?.bufferedReader()?.use { r ->
                val buf = CharArray(MAX_FEED_BYTES)
                var n = 0
                while (n < buf.size) {
                    val k = r.read(buf, n, buf.size - n)
                    if (k < 0) break
                    n += k
                }
                String(buf, 0, n)
            } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun apkFile(context: Context, version: String) = File(context.cacheDir, "sotto-update-$version.apk")

    fun cachedApk(context: Context, version: String): File? = apkFile(context, version).takeIf { it.exists() && it.length() > 0L }

    /**
     * Download the release's APK into cacheDir with 0..100 progress. Writes a .part file
     * and promotes it only when complete, and drops downloads of other versions.
     */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val out = apkFile(context, release.version)
            val tmp = File(context.cacheDir, "sotto-update-${release.version}.part")
            val src = if (release.apkUrl.startsWith(ASSET_PREFIX)) release.apkUrl else EVERGREEN_APK
            val conn = (URL(src).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Sotto/${BuildConfig.VERSION_NAME}")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
            val total = if (conn.contentLength > 0) conn.contentLength.toLong() else release.bytes
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (done < MAX_APK_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            conn.disconnect()
            // A connection that drops halfway ends the read the same way a finished one does.
            // Without this the half APK was promoted, cached as if complete, and offered to the
            // installer, which refuses it -- leaving an update that fails identically every time.
            if (done >= MAX_APK_BYTES || tmp.length() <= 0L || (total > 0 && tmp.length() != total)) {
                tmp.delete()
                return@withContext null
            }
            context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("sotto-update-") && it != tmp) it.delete() }
            if (!tmp.renameTo(out)) return@withContext null
            out
        } catch (e: CancellationException) {
            File(context.cacheDir, "sotto-update-${release.version}.part").delete()
            throw e
        } catch (e: Exception) {
            // Otherwise a download that fails at 90% leaves most of an APK in internal storage
            // for ever, and the next attempt starts from nothing beside it.
            File(context.cacheDir, "sotto-update-${release.version}.part").delete()
            null
        }
    }

    /**
     * Hand the APK to the system installer, once it is established that the file is this same
     * app signed by the same key. NEEDS_PERMISSION means "install unknown apps" is still off,
     * and the matching settings screen has been opened so the user can grant it and tap again.
     */
    /**
     * Is this APK the same app, signed by the same key as the copy that is running?
     *
     * Android refuses a differently-signed update anyway, but it refuses it after the user has
     * tapped through the installer, with a message that explains nothing -- and it does not
     * check the package name at all until then. Checking here means a wrong file is named as
     * wrong and deleted, rather than becoming an update that fails the same way every time.
     */
    private fun sameAppSameKey(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val downloaded = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching false
        if (downloaded.packageName != context.packageName) return@runCatching false
        val theirs = certificatesOf(downloaded)
        val ours = certificatesOf(pm.getPackageInfo(context.packageName, flags))
        theirs.isNotEmpty() && theirs == ours
    }.getOrDefault(false)

    private fun certificatesOf(info: android.content.pm.PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().filterNotNull()
            .map { signature -> Crypto.toHex(digest.digest(signature.toByteArray())) }
            .toSet()
    }

    enum class Install { STARTED, NEEDS_PERMISSION, NOT_OURS }

    fun install(context: Context, apk: File): Install {
        if (!sameAppSameKey(context, apk)) {
            apk.delete()
            return Install.NOT_OURS
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return Install.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(intent) }.isSuccess) Install.STARTED else Install.NEEDS_PERMISSION
    }
}
