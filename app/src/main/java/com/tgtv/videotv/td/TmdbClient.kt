package com.tgtv.videotv.td

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal TMDB metadata client. Parses a release-style file name, queries
 * TMDB's search/multi endpoint and caches the top hit per query. Uses a
 * bring-your-own API key stored via [Credentials.setTmdbKey].
 */
object TmdbClient {

    /** Project's own TMDB v3 read key, embedded so metadata works out of the box. */
    private const val BUILT_IN_KEY = "504feaa166d6202f433c3e09869ebbf5"

    data class Info(
        val title: String,
        val year: Int?,
        val rating: Double?,
        val overview: String,
        val tmdbId: Int = 0,
        val mediaType: String? = null,
        val backdropPath: String? = null,
        val posterPath: String? = null,
        val genres: List<String> = emptyList(),
        val runtimeMinutes: Int? = null,
        val tagline: String? = null,
        val status: String? = null,
    )

    /** Sentinel for "TMDB has no such title" (ConcurrentHashMap rejects nulls). */
    private val MISS = Info("", null, null, "")

    private const val WEB_UA = "TGVideoTV/0.2 (github.com/aravindbuilds/TelegramVideoTV)"

    /** Normalized query -> top result (misses cached through [MISS] to avoid refetching). */
    private val cache = ConcurrentHashMap<String, Info>()

    /** On by default; supported by the built-in key or a user override in prefs. */
    fun enabled(context: Context): Boolean = !Credentials.tmdbDisabled(context)

    /** User-entered key overrides the built-in one when present. */
    private fun apiKey(context: Context): String = Credentials.tmdbKey(context) ?: BUILT_IN_KEY

    /**
     * "Movie.Name.2023.1080p.WEB-DL.x264-GROUP.mkv" -> "Movie Name".
     * Strips the extension, bracketed tags, release-scene tokens and separators.
     */
    fun parseTitle(fileName: String): String {
        var t = fileName.substringBeforeLast('.')
        t = t.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        t = t.replace(RELEASE_TOKENS, " ")
        t = t.replace(Regex("""[._\-]+\s*"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim(' ', '-')
        return t
    }

    /** Last plausible year token in the name ("Blade.Runner.2049" -> 2049). */
    fun parseYear(fileName: String): Int? =
        Regex("""(?<![\d.])(19\d{2}|20\d{2})(?![\d.pP])""")
            .findAll(fileName)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()

    suspend fun lookup(context: Context, fileName: String): Info? {
        val apiKey = apiKey(context)
        val query = parseTitle(fileName)
        if (query.isBlank()) return null
        if (JUNK_QUERY.matches(query.trim())) {
            TdLib.log("tmdb: skip junk '$query'")
            return null
        }
        val cacheKey = query.lowercase()
        cache[cacheKey]?.let { return it.takeUnless { hit -> hit === MISS } }
        val outcome = withContext(Dispatchers.IO) {
            runCatching { fetch(apiKey, query) }
        }
        return outcome.fold(
            onSuccess = { info ->
                TdLib.log("tmdb: '$query' -> ${info?.title ?: "no match"}")
                cache[cacheKey] = info ?: MISS
                info
            },
            onFailure = { e ->
                // Transport error: transient -> do NOT cache a MISS over it
                TdLib.log("tmdb: '$query' failed: ${e.message}")
                null
            },
        )
    }

    /**
     * Progressive retries: exact query -> doubled letters collapsed (TMDB has
     * "Chadum" for files spelled "Chaadum" - transliterated titles vary) ->
     * year-tokens removed -> both -> -> then a web-assisted pass: Wikipedia /
     * DuckDuckGo give the canonical title, which is fed back into TMDB.
     * First hit wins.
     */
    private fun fetch(apiKey: String, query: String): Info? {
        searchWithRetries(apiKey, query)?.let {
            TdLib.log("tmdb: matched '$query'")
            return it
        }
        val candidates = webCandidates(query).take(4)
        var sawMiss = false
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            try {
                val info = searchWithRetries(apiKey, candidate)
                if (info != null) {
                    TdLib.log("tmdb: web-resolved '$query' -> '$candidate'")
                    return info
                }
                sawMiss = true
            } catch (_: Exception) {
                // transport error on this candidate - keep trying others
            }
        }
        if (!sawMiss && candidates.isNotEmpty()) {
            // every path failed on transport, not "no such title"
            throw IllegalStateException("all web resolution attempts failed")
        }
        return null
    }

    /** Attempt pipeline for one base title: fuzzy + year-stripped variants.
     *  Throws when every attempt died on a transport error (never the API's fault). */
    private fun searchWithRetries(apiKey: String, base: String): Info? {
        val collapsed = collapseDoubles(base)
        val yearless = removeYears(base)
        val attempts = linkedSetOf(base, collapsed, yearless, collapseDoubles(yearless))
        var attempted = 0
        var transportFails = 0
        var lastError: Exception? = null
        for (attempt in attempts) {
            if (attempt.isBlank()) continue
            attempted++
            try {
                fetchOnce(apiKey, attempt)?.let { return it }
            } catch (e: Exception) {
                transportFails++
                lastError = e
            }
        }
        if (attempted in 1..transportFails && lastError != null) throw lastError
        return null
    }

    /** Collapses runs of repeated letters: "Chaadum" -> "Chadum". */
    private fun collapseDoubles(query: String): String =
        Regex("""([a-z])\1+""")
            .replace(query.lowercase()) { m -> m.groupValues[1] }
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun removeYears(query: String): String =
        Regex("""\b(?:19|20)\d{2}\b""").replace(query, " ").replace(Regex("""\s+"""), " ").trim()

    // ---------------------------------------------------- Web title resolve

    /**
     * Films (especially transliterated Indian ones) often only match TMDB
     * under their canonical wiki spelling. Ask Wikipedia + DuckDuckGo for
     * candidate titles and let the caller feed each into TMDB.
     */
    private fun webCandidates(query: String): List<String> {
        val out = mutableListOf<String>()
        out.addAll(wikipediaTitles(query))
        out.addAll(duckDuckGoTitles(query))
        return out
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
    }

    private fun wikipediaTitles(query: String): List<String> {
        val url = URL(
            "https://en.wikipedia.org/w/api.php?action=opensearch&search=" +
                URLEncoder.encode(query, "UTF-8") + "&limit=5&namespace=0&format=json",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", WEB_UA)
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // opensearch returns [ "query", [titles], [descriptions], [urls] ]
            val titles = JSONArray(body).optJSONArray(1) ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until titles.length()) {
                cleanWebTitle(titles.optString(i))?.let { out.add(it) }
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun duckDuckGoTitles(query: String): List<String> {
        // Filename + "imdb" forgives misspellings: the top hit is the film's
        // canonical page, whose visible title carries the real name
        val ddgQuery = query + " imdb"
        val conn = URL("https://lite.duckduckgo.com/lite/").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.outputStream.use {
                it.write(("q=" + URLEncoder.encode(ddgQuery, "UTF-8")).toByteArray())
            }
            if (conn.responseCode != 200) {
                emptyList()
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                RESULT_LINK.findAll(body)
                    .map { m -> cleanWebTitle(m.groupValues[1]) }
                    .filterNotNull()
                    .take(5)
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private val RESULT_LINK = Regex(
        """class="result-link"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Drops site suffixes and parenthetical qualifiers from a result link text. */
    private fun cleanWebTitle(raw: String): String? {
        var s = raw
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            .trim()
        // Strip trailing site names: "Title - Wikipedia", "Title | IMDb", "Title" alone
        s = s.split(Regex("""\s[-|]\s""")).first().trim()
        // Strip trailing "(2025 film)" style qualifiers
        s = s.replace(Regex("""\s*\((?:19|20)?\d{2}(?:\s+(?:film|movie|channel|tv series))?\)$"""), "")
        if (s.lowercase() == "wikipedia" || s.lowercase() == "imdb") return null
        return s.ifBlank { null }
    }

    /**
     * One TMDB search request. Stale keep-alive sockets on the TV's Wi-Fi
     * intermittently reset mid-handshake ("connection reset by peer"), so each
     * request uses a fresh socket (Connection: close) and gets one retry.
     */
    private fun fetchOnce(apiKey: String, query: String): Info? {
        val url = URL(
            "https://api.themoviedb.org/3/search/multi?api_key=" + apiKey +
                "&query=" + URLEncoder.encode(query, "UTF-8") +
                "&include_adult=false&language=en-US&page=1",
        )
        return try {
            tmdbSearch(url)
        } catch (e: java.io.IOException) {
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            tmdbSearch(url)
        }
    }

    private fun tmdbSearch(url: URL): Info? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val type = r.optString("media_type")
                if (type != "movie" && type != "tv") continue
                val title = r.optString(if (type == "movie") "title" else "name")
                if (title.isBlank()) continue
                val date = r.optString(if (type == "movie") "release_date" else "first_air_date")
                val year = if (date.length >= 4) date.substring(0, 4).toIntOrNull() else null
                val rating = if (r.has("vote_average") && !r.isNull("vote_average")) {
                    r.optDouble("vote_average").takeIf { it > 0.0 }
                } else {
                    null
                }
                return Info(
                    title = title,
                    year = year,
                    rating = rating,
                    overview = r.optString("overview"),
                    tmdbId = r.optInt("id", 0),
                    mediaType = type,
                    backdropPath = r.optString("backdrop_path").takeIf { it.isNotBlank() },
                    posterPath = r.optString("poster_path").takeIf { it.isNotBlank() },
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** True when [info] is a TMDB hit that has not had details (genres/runtime) fetched yet. */
    fun needsDetails(info: Info?): Boolean =
        info != null && info.tmdbId != 0 && info.genres.isEmpty() && info.mediaType != null

    /**
     * Fetches the TMDB details page for a hit (genres, runtime, full backdrop)
     * and returns an enriched copy. Failures return the base [info] unchanged.
     */
    suspend fun enrich(context: Context, info: Info): Info {
        if (!needsDetails(info)) return info
        val enriched = withContext(Dispatchers.IO) {
            runCatching { fetchDetails(apiKey(context), info) }.getOrNull()
        }
        if (enriched != null) {
            cache[info.title.lowercase()] = enriched
        }
        return enriched ?: info
    }    private fun fetchDetails(apiKey: String, info: Info): Info {
        val url = URL(
            "https://api.themoviedb.org/3/" + info.mediaType + "/" + info.tmdbId +
                "?api_key=" + apiKey + "&language=en-US",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return info
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val d = JSONObject(body)
            val genres = mutableListOf<String>()
            d.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.getJSONObject(i).optString("name")?.takeIf { it.isNotBlank() }
                        ?.let { genres.add(it) }
                }
            }
            val runtime = when (info.mediaType) {
                "movie" -> d.optInt("runtime", 0).takeIf { it > 0 }
                else -> d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
            }
            return info.copy(
                genres = genres,
                runtimeMinutes = runtime,
                rating = info.rating ?: run {
                    if (d.has("vote_average") && !d.isNull("vote_average")) {
                        d.optDouble("vote_average").takeIf { it > 0.0 }
                    } else null
                },
                overview = info.overview.ifBlank { d.optString("overview") },
                backdropPath = info.backdropPath ?: d.optString("backdrop_path"),
                tagline = d.optString("tagline").takeIf { it.isNotBlank() },
                status = d.optString("status").takeIf { it.isNotBlank() },
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Release-scene junk that must not end up in the search query. */    private val RELEASE_TOKENS = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|360p|4k|uhd|hdr(?:10)?\+?|dv|hq|hd|dl|dolby|vision|x264|x265|h\.?264|h\.?265|hevc|avc|web-?dl|webrip|web|bluray|blu-ray|bdrip|brrip|dvdrip|dvdscr|hdtv|hdrip|hdts|cam|aac\d*|ac3|eac3|ddp?\d*(?:\.\d)?|dts(?:-hd)?|truehd|atmos|mp3|flac|10bit|8bit|esubs?|hc|proper|repack|extended|unrated|remastered|multi|dual(?:\s?audio)?|internal|amzn|nf|dsnp|hulu|max|itunes|netflix|yify|rargb|evo|tgx|megapek|adjutant|skillhunt|malaya|mkv|mp4|avi|m(?:kv|ov)|webm|m4v|mpg|mpeg|wmv|flv|m2ts|mts|hindi|english|tamil|telugu|malayalam|kannada|marathi|punjabi|bhojpuri|bengali|gujarati|urdu|japanese|korean|chinese|mandarin|cantonese|spanish|french|german|italian|russian|turkish|arabic|persian|portuguese)\b""",
    )

    /** Camera/auto-generated file names that must never be searched on TMDB. */
    private val JUNK_QUERY = Regex(
        """(?i)^(?:img|dsc|mvi|vid|video|movie|vig|vin|tmp|record|screen|screenshot|whatsapp)[ ._-]*(?:\d{1,8})?$""",
    )
}
