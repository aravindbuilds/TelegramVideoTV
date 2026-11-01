package com.tgtv.videotv.td

import android.content.Context
import com.tgtv.videotv.model.Watchlist
import com.tgtv.videotv.store.VideoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cloud backup / restore of the whole TGTV state through the user's own
 * Saved Messages (the NoxMedia "#NoxMediaBackup" pattern, adapted).
 *
 * A small JSON document is uploaded to Saved Messages with the caption
 * "#TGTVBackup", and only the newest copy is kept. It carries:
 *
 *   - pinned library chats (Settings -> Add chats)
 *   - My List (watchlist)
 *   - subtitle / TMDB preferences
 *   - the full watch-progress table
 *
 * Restore merges are additive and per-entry newest-wins (progress), union
 * (pinned chats, My List) - a restore can never delete anything the user
 * set locally on this device. Manual preference changes on this device win
 * over older backups (guarded by "edited" flags in [Credentials]).
 *
 * This is what makes a reinstall, a new TV box, or pairing the phone app a
 * zero-setup experience: log in, and the library/progress come back.
 */
object CloudBackup {

    /** Caption identifying our backup documents - shared with the phone app. */
    private const val CAPTION = "#TGTVBackup"

    private const val PREF_LAST_BACKUP = "cloud_backup_at"
    private const val PREF_LAST_APPLIED = "cloud_backup_applied_at"
    /** Minimum spacing between automatic backups (manual ones always run). */
    private const val AUTO_BACKUP_INTERVAL_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // ------------------------------------------------------------- backup

    /**
     * Requests a backup. Automatic callers (settings changes, progress
     * saves) are throttled; pass [force] for the explicit "Backup" action.
     */
    fun request(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            val last = prefs(app).getLong(PREF_LAST_BACKUP, 0L)
            if (!force && System.currentTimeMillis() - last < AUTO_BACKUP_INTERVAL_MS) return@launch
            mutex.withLock { runCatching { backup(app) } }
        }
    }

    private suspend fun backup(context: Context) {
        val savedId = TdLib.me?.id ?: return
        if (!TdLib.sessionIsUser) return

        val progress = JSONObject()
        VideoLibrary.progressAll(context).forEach { (k, v) ->
            progress.put(k, JSONObject().put("p", v.first).put("d", v.second).put("u", v.third))
        }
        val watchlist = JSONArray()
        Watchlist.all(context).forEach { v ->
            watchlist.put(
                JSONObject()
                    .put("messageId", v.messageId)
                    .put("chatTitle", v.chatTitle)
                    .put("title", v.title)
                    .put("fileId", v.fileId)
                    .put("fileSize", v.fileSize)
                    .put("durationSeconds", v.durationSeconds)
                    .put("mimeType", v.mimeType)
                    .put("fileName", v.fileName)
                    .put("thumbnailFileId", v.thumbnailFileId),
            )
        }
        val state = JSONObject()
            .put("app", "TGTV")
            .put("v", 1)
            .put("at", System.currentTimeMillis())
            .put("pinnedChats", JSONArray(Credentials.libraryChats(context).toList()))
            .put("subsEnabled", Credentials.subsEnabled(context))
            .put("subsTextSize", Credentials.subsTextSize(context))
            .put("subsLanguages", Credentials.subsLanguages(context))
            .put("tmdbDisabled", Credentials.tmdbDisabled(context))
            .put("watchlist", watchlist)
            .put("progress", progress)

        // Upload as a tiny document; the caption is what identifies it later
        val tmp = File(context.cacheDir, "tgtv-backup.json").apply { writeText(state.toString()) }
        val sent = TdLib.send(
            TdApi.SendMessage(
                savedId,
                null,
                null,
                null,
                null,
                TdApi.InputMessageDocument(
                    TdApi.InputDocument(TdApi.InputFileLocal(tmp.absolutePath), null, true),
                    TdApi.FormattedText(CAPTION, null),
                ),
            ),
        )

        // Keep exactly one backup document around
        val old = findBackups(savedId).filter { it.id != sent.id }
        if (old.isNotEmpty()) {
            runCatching {
                TdLib.send(TdApi.DeleteMessages(savedId, old.map { it.id }.toLongArray(), false))
            }
        }
        prefs(context).edit().putLong(PREF_LAST_BACKUP, state.optLong("at")).apply()
        TdLib.log("backup: uploaded ($CAPTION)")
        tmp.delete()
    }

    // ------------------------------------------------------------- restore

    /**
     * Merges the newest cloud backup into this device. Safe to call on
     * every startup: each backup snapshot is applied at most once
     * (newest-wins merging makes repeated application a no-op anyway).
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        scope.launch { mutex.withLock { runCatching { restoreNow(app) } } }
    }

    private suspend fun restoreNow(context: Context) {
        val savedId = TdLib.me?.id ?: return
        if (!TdLib.sessionIsUser) return
        val backups = findBackups(savedId)
        val newest = backups.maxByOrNull { it.date } ?: return
        val applied = prefs(context).getLong(PREF_LAST_APPLIED, 0L)
        if (newest.date * 1000L <= applied) return

        val doc = newest.content as? TdApi.MessageDocument
        val fileId = doc?.document?.document?.id ?: 0
        if (fileId == 0) return
        val file = TdLib.downloadFully(fileId)
        val json = runCatching { JSONObject(File(file.local.path).readText()) }.getOrNull() ?: return

        // Progress: per-entry newest wins
        json.optJSONObject("progress")?.let { VideoLibrary.restoreProgress(context, it) }

        // Pinned chats + watchlist: union, never remove local entries
        val backupPins = json.optJSONArray("pinnedChats")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
            .orEmpty()
        if (backupPins.isNotEmpty()) {
            Credentials.setLibraryChats(context, Credentials.libraryChats(context) + backupPins)
        }
        json.optJSONArray("watchlist")?.let { arr ->
            for (i in arr.length() - 1 downTo 0) {
                val item = decodeWatchlistItem(arr.optJSONObject(i) ?: continue) ?: continue
                if (!Watchlist.contains(context, item)) Watchlist.toggle(context, item)
            }
        }

        // Preferences: only when the user never touched them on this device
        if (!Credentials.subsEdited(context)) {
            if (json.has("subsEnabled")) Credentials.setSubsEnabled(context, json.optBoolean("subsEnabled", true))
            if (json.has("subsTextSize")) Credentials.setSubsTextSize(context, json.optInt("subsTextSize", 0))
            if (json.has("subsLanguages")) Credentials.setSubsLanguages(context, json.optString("subsLanguages", "en"))
        }
        if (!Credentials.tmdbEdited(context) && json.has("tmdbDisabled")) {
            Credentials.setTmdbDisabled(context, json.optBoolean("tmdbDisabled", false))
        }

        prefs(context).edit().putLong(PREF_LAST_APPLIED, newest.date * 1000L).apply()
        TdLib.log("backup: restored from ${newest.date}")
    }

    /** All "#TGTVBackup" documents in Saved Messages, newest first. */
    private suspend fun findBackups(savedId: Long): List<TdApi.Message> {
        val found = runCatching {
            TdLib.send(TdApi.SearchChatMessages(savedId, null, CAPTION, null, 0L, 0, 20, null))
        }.getOrNull() ?: return emptyList()
        return found.messages
            ?.filter { (it.content as? TdApi.MessageDocument)?.caption?.text?.contains(CAPTION) == true }
            .orEmpty()
    }

    private fun decodeWatchlistItem(o: JSONObject) = runCatching {
        com.tgtv.videotv.model.VideoItem(
            messageId = o.getLong("messageId"),
            chatTitle = o.optString("chatTitle"),
            title = o.optString("title", o.optString("fileName")),
            fileId = o.getInt("fileId"),
            fileSize = o.getLong("fileSize"),
            durationSeconds = o.getInt("durationSeconds"),
            mimeType = o.optString("mimeType"),
            fileName = o.optString("fileName", o.optString("title")),
            thumbnailFileId = o.getInt("thumbnailFileId"),
        )
    }.getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences("tgvideotv", Context.MODE_PRIVATE)
}
