package com.tgtv.videotv.sub

import android.content.Context
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenSubtitles.com fallback: when a file has no embedded subtitle tracks the
 * player calls [fetchFor], which (1) resolves the video through
 * [TmdbClient] to a stable TMDB id, (2) searches OpenSubtitles for that id in
 * the configured languages, (3) downloads the best subtitle file into the
 * app cache and returns it for side-loading into ExoPlayer.
 *
 * Bring-your-own API key from opensubtitles.com (free account -> API keys);
 * without a key web subtitles stay disabled.
 */
object SubtitlesClient {

    private const val WEB_UA = "TGTV/0.3 (github.com/aravindbuilds/TelegramVideoTV)"

    fun hasKey(context: Context): Boolean = !Credentials.openSubtitlesKey(context).isNullOrBlank()

    data class Fetched(val file: File, val language: String, val label: String)

    /**
     * Resolves [video] to a TMDB id via the shared [TmdbClient] lookup and
     * returns the best OpenSubtitles file in the user's languages.
     */
    suspend fun fetchFor(context: Context, video: VideoItem): Fetched? =
        withContext(Dispatchers.IO) { runCatching { runFetch(context, video) }.getOrNull() }

    private suspend fun runFetch(context: Context, video: VideoItem): Fetched? {
        val apiKey = Credentials.openSubtitlesKey(context) ?: return null
        val title = video.title.ifBlank { video.fileName }
        val info = when {
            TmdbClient.enabled(context) ->
                TmdbClient.lookup(context, title)?.let { TmdbClient.enrich(context, it) }
            else -> null
        }
        val languages = Credentials.subsLanguages(context)
        val entry = when {
            info != null && info.tmdbId != 0 ->
                searchEntry(apiKey, "tmdb_id=" + info.tmdbId, languages)
            else ->
                searchEntry(apiKey, "query=" + URLEncoder.encode(title, "UTF-8"), languages)
        } ?: return null

        val srt = downloadFile(apiKey, entry.fileId, context, video.fileId) ?: return null
        val label = entry.label.ifBlank {
            info?.let { "${it.title} (${it.year ?: ""})" } ?: title
        }
        TdLib.log("subs: downloaded ${Languages.nameOf(entry.language)} from $label")
        return Fetched(srt, entry.language, label)
    }

    private data class Entry(val fileId: Long, val language: String, val label: String)

    /** GET /api/v1/subtitles with a raw suffix query ("tmdb_id=123", "query=..."). */
    private fun searchEntry(apiKey: String, suffix: String, languages: String): Entry? {
        val conn = URL(
            "https://api.opensubtitles.com/api/v1/subtitles?" + suffix +
                "&languages=" + languages + "&format=srt",
        ).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: continue
                val language = attrs.optString("language").takeIf { it.isNotBlank() } ?: "xx"
                val label = attrs.optString("release").takeIf { it.isNotBlank() }
                    ?: attrs.optString("movie_title_plain", "")
                val files = attrs.optJSONArray("files") ?: continue
                // Prefer the first srt file; filter AI flag unavailable in this API tier
                for (j in 0 until files.length()) {
                    val f = files.getJSONObject(j)
                    val name = f.optString("file_name")
                    val ext = name.substringAfterLast('.', "srt").lowercase()
                    if (ext in setOf("srt", "ass", "vtt", "ssa")) {
                        val id = f.optLong("file_id", 0L)
                        if (id > 0) return Entry(id, language, label)
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST /api/v1/download returns a temporary CDN link; the actual bytes may
     * still be delivered behind a follow. Returns the cached .srt file.
     */
    private fun downloadFile(apiKey: String, fileId: Long, context: Context, videoFileId: Int): File? {
        val conn = URL("https://api.opensubtitles.com/api/v1/download")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("file_id", fileId).toString().toByteArray())
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val link = JSONObject(body).optString("link")
            if (link.isBlank()) return null
            return File(downloadedBytes(context, link, videoFileId))
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadedBytes(context: Context, link: String, videoFileId: Int): String {
        val f = File(context.cacheDir, "subs_$videoFileId.srt")
        val conn = URL(link).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("subtitle download failed (${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            if (f.length() == 0L) throw IllegalStateException("empty subtitle response")
            return f.absolutePath
        } finally {
            conn.disconnect()
        }
    }

    /** Drops subtitle files older than a day so the cache dir cannot refill forever. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        context.cacheDir.listFiles { f ->
            f.name.startsWith("subs_") && f.lastModified() < cutoff
        }?.forEach { it.delete() }
    }
}

/** Tiny helper for nicer subtitle names in the player track selector. */
object Languages {
    fun nameOf(iso: String): String {
        val base = iso.substringBefore('-')
        return java.util.Locale.forLanguageTag(base).displayLanguage.ifBlank { iso }
            .replaceFirstChar { it.uppercase() }
    }
}
