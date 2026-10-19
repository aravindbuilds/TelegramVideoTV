package com.tgtv.videotv.ui

import com.tgtv.videotv.model.VideoItem
import org.drinkless.tdlib.TdApi

class VideoRow(val chatId: Long, val title: String, val items: List<VideoItem>, val hasMore: Boolean) {
    companion object {
        fun from(chat: TdApi.Chat, items: List<VideoItem>, hasMore: Boolean) =
            VideoRow(chat.id, chat.title, items, hasMore)
    }
}
