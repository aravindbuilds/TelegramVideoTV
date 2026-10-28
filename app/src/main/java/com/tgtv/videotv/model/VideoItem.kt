package com.tgtv.videotv.model

import java.io.Serializable

/**
 * A playable video item, mapped from any Telegram message content that carries video:
 * [TdApi.MessageVideo], [TdApi.MessageVideoNote], [TdApi.MessageAnimation] or a
 * [TdApi.MessageDocument] whose MIME type / file extension looks like a video
 * (most large videos on Telegram are sent as documents).
 */
data class VideoItem(
    val messageId: Long,
    val chatTitle: String,
    val title: String,
    val fileId: Int,
    val fileSize: Long,
    val durationSeconds: Int,
    val mimeType: String,
    val fileName: String,
    val thumbnailFileId: Int,
    /** Source chat; needed to identity-hide single videos across chats. */
    val chatId: Long = 0,
    /**
     * Stable TDLib remote-file identifier. TDLib's numeric file ids die with
     * the session - stored references become "file unspecified" on restart -
     * so playback resolves this back to a live [TdApi.File] instead.
     */
    val remoteFileId: String = "",
    /** "video" | "animation" | "videoNote" | "document", for remote re-resolve. */
    val fileKind: String = "",
) : Serializable {
    fun prettySize(): String {
        val s = fileSize.toFloat()
        return when {
            fileSize <= 0L -> ""
            s >= 1024f * 1024f * 1024f -> "%.1f GB".format(s / (1024f * 1024f * 1024f))
            else -> "%.1f MB".format(s / (1024f * 1024f))
        }
    }

    fun prettyDuration(): String {
        val d = durationSeconds
        if (d <= 0) return ""
        return "%02d:%02d".format(d / 60, d % 60)
    }

    /** "1h 28m" style runtime line for the details screen. */
    fun prettyRuntime(): String {
        val d = durationSeconds
        if (d <= 0) return ""
        return (if (d / 3600 > 0) "${d / 3600}h " else "") + "%02dm".format(d % 3600 / 60)
    }
}
