package com.tgtv.videotv.td

import android.content.Context
import com.tgtv.videotv.store.VideoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi

/**
 * Cross-device watch-progress sync, riding plain Telegram messages.
 *
 * When playback stops, TGTV posts a tiny text bookmark REPLYING to the video
 * message in the same chat, e.g. a video in Saved Messages gets:
 *
 *     TGTV ▶ 7523000 5400000      (positionMs + durationMs)
 *
 * The other device (phone app) sees the bookmark during its normal history
 * scan, parses it and merges the position into its own store - newest
 * timestamp wins. Finished videos get the tombstone form "TGTV ▶ 0 0", which
 * clears the resume on both devices. Bookmarks are only posted in Saved
 * Messages: a group chat would show them to everyone.
 *
 * Same message thread doubles as the transport both ways, so no server, no
 * bot and no setup is needed - it works because both apps share the Telegram
 * account.
 */
object ProgressSync {

    /** Marker prefix shared verbatim with the phone app - do not change. */
    private const val MARK = "TGTV ▶"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes all bookmark network operations (post/edit/delete). */
    private val netMutex = Mutex()

    /** Parses a bookmark message -> (videoMessageId, posMs, durMs); null when not a bookmark. */
    fun parseMessage(msg: TdApi.Message): Triple<Long, Long, Long>? {
        val text = (msg.content as? TdApi.MessageText)?.text?.text ?: return null
        if (!text.startsWith(MARK)) return null
        val reply = msg.replyTo as? TdApi.MessageReplyToMessage ?: return null
        val parts = text.removePrefix(MARK).trim().split(' ')
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return Triple(reply.messageId, pos, dur)
    }

    /**
     * Called from the history scan / live updates for EVERY message in a
     * chat: bookmarks are merged into the local store, everything else is
     * left alone. Returns true when [msg] was a bookmark.
     */
    fun observeMessage(context: Context, chatId: Long, msg: TdApi.Message): Boolean {
        val bookmark = parseMessage(msg) ?: return false
        val (videoMsgId, pos, dur) = bookmark
        VideoLibrary.applyRemote(context, chatId, videoMsgId, pos, dur, msg.date * 1000L)
        return true
    }

    /**
     * Posts or updates the bookmark for [videoMsgId] after a local save /
     * clear. Fire-and-forget: playback must never wait on Telegram. Only
     * acts in user sessions and only for Saved Messages videos - group
     * members must not see playback bookmarks.
     */
    fun sync(chatId: Long, videoMsgId: Long, positionMs: Long, durationMs: Long) {
        scope.launch {
            // ownChat() both confirms this is a user session and waits out
            // the fresh-login race (SendMessage to the own id otherwise
            // dies with "Chat not found" right after a QR sign-in)
            val savedId = TdLib.ownChat()?.id ?: return@launch
            if (chatId != savedId || videoMsgId == 0L) return@launch
            netMutex.withLock { runCatching { postOrUpdate(chatId, videoMsgId, positionMs, durationMs) } }
        }
    }

    /** Finds our bookmark for [videoMsgId] and edits it, or posts a fresh one. */
    private suspend fun postOrUpdate(chatId: Long, videoMsgId: Long, pos: Long, dur: Long) {
        val text = "$MARK $pos $dur"
        val existing = findBookmarks(chatId, videoMsgId)
        // Keep the newest bookmark, tombstone the stragglers (two devices
        // can post concurrently before either sees the other's message)
        val target = existing.firstOrNull()
        existing.drop(1).forEach { old ->
            runCatching { TdLib.send(TdApi.DeleteMessages(chatId, longArrayOf(old), false)) }
        }
        if (target != null) {
            runCatching {
                TdLib.send(
                    TdApi.EditMessageText(
                        chatId,
                        target,
                        null,
                        TdApi.InputMessageText(TdApi.FormattedText(text, null), null, false),
                    ),
                )
            }.onFailure {
                // Edits stop working 48h after posting -> replace the marker
                runCatching { TdLib.send(TdApi.DeleteMessages(chatId, longArrayOf(target), false)) }
                postBookmark(chatId, videoMsgId, text)
            }
        } else {
            postBookmark(chatId, videoMsgId, text)
        }
        TdLib.log("sync: bookmark '$text' for msg=$videoMsgId")
    }

    /** Message ids of our bookmarks (incl. tombstones) replying to [videoMsgId]. */
    private suspend fun findBookmarks(chatId: Long, videoMsgId: Long): List<Long> {
        val found = runCatching {
            TdLib.send(
                TdApi.SearchChatMessages(chatId, null, MARK, null, 0L, 0, 50, null),
            )
        }.getOrNull() ?: return emptyList()
        return found.messages
            ?.filter { msg ->
                msg.replyTo.let { it is TdApi.MessageReplyToMessage && it.messageId == videoMsgId } &&
                    (msg.content as? TdApi.MessageText)?.text?.text?.startsWith(MARK) == true
            }
            ?.map { it.id }
            .orEmpty()
    }

    private suspend fun postBookmark(chatId: Long, videoMsgId: Long, text: String) {
        TdLib.send(
            TdApi.SendMessage(
                chatId,
                null,
                TdApi.InputMessageReplyToMessage(videoMsgId, null, 0, null),
                null,
                null,
                TdApi.InputMessageText(TdApi.FormattedText(text, null), null, false),
            ),
        )
    }
}
