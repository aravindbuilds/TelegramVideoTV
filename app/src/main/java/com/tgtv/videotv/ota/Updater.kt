package com.tgtv.videotv.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub-Releases OTA updater for TGTV (ported from the phone app).
 *
 * How a release becomes an update:
 *   1. Bump versionName in app/build.gradle.kts (e.g. "1.2").
 *   2. Push a git tag `v1.2` and attach the built APK as a release asset.
 *   3. The app compares the release tag against its own versionName; when the
 *      tag is newer it downloads the APK asset and hands it to the system
 *      package installer.
 *
 * Tags are compared as digit-group tuples ("v1.10" > "v1.9"), so a plain
 * vX.Y scheme is enough - no ordering metadata is fetched from GitHub.
 */
object Updater {

    /** GitHub repo polled for releases. */
    const val REPO = "aravindbuilds/TelegramVideoTV"

    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val REPO_API = "https://api.github.com/repos/$REPO"

    private const val UA = "TGTV-AndroidTV (github.com/aravindbuilds/TelegramVideoTV)"

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val prerelease: Boolean = false,
    )

    /** This build's versionName (e.g. "1.2"). */
    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** Where the downloaded APK lands; shared with the installer via FileProvider. */
    fun apkFile(context: Context): File = File(File(context.cacheDir, "ota"), "update.apk")

    /**
     * Fetches the release the app should offer: the latest stable by default,
     * or - when [betas] is on - the newest entry (stable or pre-release) with
     * an APK asset. Null when GitHub is unreachable / nothing has an APK.
     */
    suspend fun fetchLatest(betas: Boolean = false): Release? = withContext(Dispatchers.IO) {
        runCatching {
            if (betas) {
                fetchBetaFromList()
            } else {
                apkFrom(JSONObject(get(LATEST_URL) ?: return@runCatching null), prerelease = false)
            }
        }.getOrNull()
    }

    /** GET with GitHub-API headers; null on any non-200 / transport failure. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Walks the newest releases top-down and returns the first one that is
     * allowed (pre-releases are permitted here) and carries an APK. The list
     * is newest-first, so the first match is the newest candidate.
     */
    private fun fetchBetaFromList(): Release? {
        val body = get("$REPO_API/releases?per_page=10") ?: return null
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            apkFrom(o, o.optBoolean("prerelease"))?.let { return it }
        }
        return null
    }

    /**
     * Builds a [Release] from one release object, or null without an APK
     * asset. Asset pick: the **-v7a** APK plays on every device (arm64 boxes
     * included) so it is preferred; the -universal one (~4x the size) is only
     * for exotic ABIs.
     */
    private fun apkFrom(obj: JSONObject, prerelease: Boolean): Release? {
        val assets = obj.optJSONArray("assets") ?: return null
        var v7a: Pair<String, Long>? = null
        var universal: Pair<String, Long>? = null
        var any: Pair<String, Long>? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val entry = a.optString("browser_download_url", "") to a.optLong("size", 0L)
            if (name.contains("-v7a", ignoreCase = true) && v7a == null) v7a = entry
            if (name.contains("universal", ignoreCase = true) && universal == null) universal = entry
            if (any == null) any = entry
        }
        val (url, size) = v7a ?: universal ?: any ?: return null
        if (url.isBlank()) return null
        return Release(
            tag = obj.optString("tag_name", "").trimStart('v', 'V'),
            title = obj.optString("name", ""),
            notes = obj.optString("body", ""),
            apkUrl = url,
            sizeBytes = size,
            prerelease = prerelease,
        )
    }

    /**
     * True when [latest] is strictly newer than [installed]. Digit groups are
     * compared pairwise ("1.10" beats "1.9"); missing groups count as 0.
     */
    fun isNewer(latest: String, installed: String): Boolean {
        val a = latest.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        val b = installed.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Downloads the release APK to [apkFile], reporting progress as a 0..100
     * percentage. Returns the file, or null on network failure.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dest = apkFile(context)
            dest.parentFile?.mkdirs()
            dest.delete()
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode !in 200..299) return@runCatching null
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                var lastPct = -1
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            dest
        }.getOrNull()
    }

    /** Outcome of trying to hand the downloaded APK to the system installer. */
    enum class InstallResult {
        /** Installer is running; the update flow is done. */
        LAUNCHED,

        /** The user must first grant "Install unknown apps"; its settings
         *  page was opened. The APK is kept - retry the install afterwards. */
        NEEDS_PERMISSION,

        /** The installer could not be started. The APK is kept so the
         *  install can be retried (no automatic re-download). */
        FAILED,

        /** No downloaded APK on disk (should not happen while READY). */
        NO_APK,
    }

    /**
     * Hands the APK to the system installer. On Android 8+ the user must once
     * allow installs from this app; when not granted, the unknown-sources
     * settings page opens and the caller should retry afterwards. Never
     * deletes [apk] - a failed install must not cost a re-download.
     */
    fun install(context: Context, apk: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return InstallResult.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            InstallResult.LAUNCHED
        } else {
            InstallResult.FAILED
        }
    }

    /** Drops a finished/corrupt download so the next check starts clean. */
    fun clearDownload(context: Context) {
        apkFile(context).delete()
    }
}
