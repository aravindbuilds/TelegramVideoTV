package com.tgtv.videotv.store

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.model.Release

/**
 * A tiny index of video references (never the video bytes - those stay on
 * Telegram). Two tables:
 *
 *  - `videos`  : chat_id + message_id + everything [VideoItem] needs, plus the
 *                message date. This ends the "bots cannot read chat history"
 *                reload hassle: the library builds instantly from here and
 *                live captures keep it fresh.
 *  - `hidden`  : entries removed from TGTV ("Remove" action) - the post stays
 *                in Telegram, it just stops appearing here, also across
 *                re-scans of user-mode history.
 */
object VideoLibrary {

    private const val DB_NAME = "tgtv_videos.db"
    private const val DB_VERSION = 3

    private const val T_VIDEOS = "videos"
    private const val T_HIDDEN = "hidden"
    private const val T_PROGRESS = "progress"

    private val hiddenCache = HashSet<String>()

    private fun helper(context: Context): Helper =
        helperRef ?: Helper(context.applicationContext).also { helperRef = it }

    private var helperRef: Helper? = null

    private fun db(context: Context): SQLiteDatabase = helper(context).writableDatabase

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_VIDEOS (" +
                    "chat_id INTEGER NOT NULL, " +
                    "message_id INTEGER NOT NULL, " +
                    "chat_title TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "file_id INTEGER NOT NULL, " +
                    "file_size INTEGER NOT NULL, " +
                    "duration INTEGER NOT NULL, " +
                    "mime TEXT NOT NULL, " +
                    "file_name TEXT NOT NULL, " +
                    "thumb_file_id INTEGER NOT NULL, " +
                    "msg_date INTEGER NOT NULL, " +
                    "remote_file_id TEXT NOT NULL DEFAULT '', " +
                    "file_kind TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(chat_id, message_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_HIDDEN (" +
                    "chat_id INTEGER NOT NULL, " +
                    "message_id INTEGER NOT NULL, " +
                    "PRIMARY KEY(chat_id, message_id))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $T_PROGRESS (" +
                    "chat_id INTEGER NOT NULL, " +
                    "message_id INTEGER NOT NULL, " +
                    "position_ms INTEGER NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, " +
                    "updated_ms INTEGER NOT NULL, " +
                    "PRIMARY KEY(chat_id, message_id))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: keep stored references (re-training loses bot-only knowledge)
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $T_VIDEOS ADD COLUMN remote_file_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $T_VIDEOS ADD COLUMN file_kind TEXT NOT NULL DEFAULT ''")
            }
            // v3: per-title resume position, kept apart from `videos` so
            // library rescans (INSERT OR REPLACE) never wipe watch state
            if (oldVersion < 3) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS $T_PROGRESS (" +
                        "chat_id INTEGER NOT NULL, " +
                        "message_id INTEGER NOT NULL, " +
                        "position_ms INTEGER NOT NULL, " +
                        "duration_ms INTEGER NOT NULL, " +
                        "updated_ms INTEGER NOT NULL, " +
                        "PRIMARY KEY(chat_id, message_id))",
                )
            }
        }
    }

    // ------------------------------------------------------------ write

    /** Stores / refreshes one video reference; skipped when already hidden. */
    fun upsert(context: Context, item: VideoItem, messageDate: Int) {
        if (isHidden(context, item.chatId, item.messageId)) return
        val st = db(context).compileStatement(
            "INSERT OR REPLACE INTO $T_VIDEOS " +
                "(chat_id,message_id,chat_title,title,file_id,file_size,duration,mime,file_name,thumb_file_id,msg_date,remote_file_id,file_kind) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        st.bindLong(1, item.chatId)
        st.bindLong(2, item.messageId)
        st.bindString(3, item.chatTitle)
        st.bindString(4, item.title)
        st.bindLong(5, item.fileId.toLong())
        st.bindLong(6, item.fileSize)
        st.bindLong(7, item.durationSeconds.toLong())
        st.bindString(8, item.mimeType)
        st.bindString(9, item.fileName)
        st.bindLong(10, item.thumbnailFileId.toLong())
        st.bindLong(11, messageDate.toLong())
        st.bindString(12, item.remoteFileId)
        st.bindString(13, item.fileKind)
        st.executeInsert()
    }

    /** "Remove from TGTV": hides it locally now and hides future re-finds too. */
    fun hide(context: Context, chatId: Long, messageId: Long) {
        val d = db(context)
        d.insertWithOnConflict(
            T_HIDDEN,
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        d.delete(T_VIDEOS, "chat_id=? AND message_id=?", arrayOf("$chatId", "$messageId"))
        hiddenCache.add(key(chatId, messageId))
    }

    fun isHidden(context: Context, chatId: Long, messageId: Long): Boolean {
        if (hiddenCache.isEmpty()) warmHiddenCache(context)
        return key(chatId, messageId) in hiddenCache
    }

    // -------------------------------------------------------- watch progress

    /** Persists the last playback position (ms) for resume-on-open. */
    fun saveProgress(
        context: Context,
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (chatId == 0L || messageId == 0L || positionMs <= 0L) return
        db(context).insertWithOnConflict(
            T_PROGRESS,
            null,
            ContentValues().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("position_ms", positionMs)
                put("duration_ms", durationMs)
                put("updated_ms", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Stored (positionMs, durationMs) for a title, or null when never started. */
    fun progressOf(context: Context, chatId: Long, messageId: Long): Pair<Long, Long>? {
        if (chatId == 0L || messageId == 0L) return null
        var out: Pair<Long, Long>? = null
        helper(context).readableDatabase.rawQuery(
            "SELECT position_ms, duration_ms FROM $T_PROGRESS WHERE chat_id=? AND message_id=?",
            arrayOf("$chatId", "$messageId"),
        ).use { c ->
            if (c.moveToFirst()) {
                out = c.getLong(0) to c.getLong(1)
            }
        }
        return out
    }

    /** Drops the resume row (position finished / played again from the start). */
    fun clearProgress(context: Context, chatId: Long, messageId: Long) {
        if (chatId == 0L || messageId == 0L) return
        db(context).delete(
            T_PROGRESS,
            "chat_id=? AND message_id=?",
            arrayOf("$chatId", "$messageId"),
        )
    }

    private fun key(chatId: Long, messageId: Long) = "$chatId:$messageId"

    private fun warmHiddenCache(context: Context) {
        val fresh = HashSet<String>()
        helper(context).readableDatabase.rawQuery(
            "SELECT chat_id, message_id FROM $T_HIDDEN",
            null,
        ).use { c ->
            while (c.moveToNext()) fresh.add(key(c.getLong(0), c.getLong(1)))
        }
        hiddenCache.clear()
        hiddenCache.addAll(fresh)
    }

    // ------------------------------------------------------------ read

    private fun visible(context: Context, cursor: Cursor): List<VideoItem> =
        cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val chatId = c.getLong(c.getColumnIndexOrThrow("chat_id"))
                    val messageId = c.getLong(c.getColumnIndexOrThrow("message_id"))
                    if (key(chatId, messageId) in hiddenCache) continue
                    add(
                        VideoItem(
                            messageId = messageId,
                            chatTitle = c.getString(c.getColumnIndexOrThrow("chat_title")),
                            title = c.getString(c.getColumnIndexOrThrow("title")),
                            fileId = c.getLong(c.getColumnIndexOrThrow("file_id")).toInt(),
                            fileSize = c.getLong(c.getColumnIndexOrThrow("file_size")),
                            durationSeconds = c.getInt(c.getColumnIndexOrThrow("duration")),
                            mimeType = c.getString(c.getColumnIndexOrThrow("mime")),
                            fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
                            thumbnailFileId =
                            c.getLong(c.getColumnIndexOrThrow("thumb_file_id")).toInt(),
                            chatId = chatId,
                            remoteFileId =
                            c.getString(c.getColumnIndexOrThrow("remote_file_id")) ?: "",
                            fileKind =
                            c.getString(c.getColumnIndexOrThrow("file_kind")) ?: "",
                        ),
                    )
                }
            }
        }

    /** Every stored video except hidden ones, newest message first. */
    fun all(context: Context): List<VideoItem> {
        warmHiddenCache(context)
        return visible(
            context,
            helper(context).readableDatabase.rawQuery(
                "SELECT * FROM $T_VIDEOS ORDER BY msg_date DESC",
                null,
            ),
        )
    }

    fun chatItems(context: Context, chatId: Long): List<VideoItem> {
        if (hiddenCache.isEmpty()) warmHiddenCache(context)
        return visible(
            context,
            helper(context).readableDatabase.rawQuery(
                "SELECT * FROM $T_VIDEOS WHERE chat_id=? ORDER BY msg_date DESC",
                arrayOf("$chatId"),
            ),
        )
    }

    /**
     * Distinct chats that have at least one visible stored video, as
     * (chatId, chatTitle, newestMessageDate) sorted by that date descending.
     * Lets the library surface known chats even before TDLib has announced
     * them (bots only learn chats when they see new messages).
     */
    fun chatIds(context: Context): List<Triple<Long, String, Int>> {
        warmHiddenCache(context)
        val rows = mutableListOf<Triple<Long, String, Int>>()
        helper(context).readableDatabase.rawQuery(
            "SELECT chat_id, " +
                "MAX(chat_title) AS title, " +
                "MAX(msg_date) AS newest " +
                "FROM $T_VIDEOS GROUP BY chat_id ORDER BY newest DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Triple(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getInt(2),
                    ),
                )
            }
        }
        return rows
    }

    /** Text for the hero chip: "1080p WEB-DL English", or a SD/HD fallback. */
    fun qualityLabel(item: VideoItem): String {
        val described = Release.describe(item.fileName)
        return described.ifBlank {
            if (item.fileSize >= 3L * 1024 * 1024 * 1024) "HD" else "SD"
        }
    }
}
