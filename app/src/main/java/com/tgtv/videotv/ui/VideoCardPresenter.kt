package com.tgtv.videotv.ui

import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.td.TdLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Renders one video poster card; thumbnail is downloaded lazily through TDLib. */
class VideoCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 400
        private const val CARD_HEIGHT = 225

        /** thumbFileId -> decoded bitmap, shared across recycled rows. */
        private val thumbCache = HashMap<Int, android.graphics.Bitmap>()
    }

    /** Called when the user long-presses a card. Null = no download handler attached. */
    var onLongPress: ((VideoItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = viewHolder.view as ImageCardView
        val video = item as VideoItem
        card.titleText = video.title
        card.contentText = listOf(
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
        ).filterNotNull().joinToString(" • ").ifBlank {
            video.chatTitle
        }

        card.setOnLongClickListener { v ->
            (v.tag as? VideoItem)?.let { onLongPress?.invoke(it) }
            true
        }
        // Tag carries the item so the long-press callback can read it even though
        // the view may be recycled before the next bind.
        card.tag = video

        // Tag prevents a recycled view showing the previous card's thumbnail
        card.mainImage = null
        if (video.thumbnailFileId == 0) return

        synchronized(thumbCache) {
            thumbCache[video.thumbnailFileId]?.let { cached ->
                card.mainImage = cached.toDrawable(card.resources)
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val bmp = runCatching {
                synchronized(thumbCache) { thumbCache[video.thumbnailFileId] }
                    ?: run {
                        val f = TdLib.downloadFully(video.thumbnailFileId, priority = 1)
                        decodeDownscaled(f.local?.path ?: return@launch, CARD_WIDTH, CARD_HEIGHT)
                            ?.also { bmp ->
                                synchronized(thumbCache) {
                                    if (thumbCache.size > 300) thumbCache.clear()
                                    thumbCache[video.thumbnailFileId] = bmp
                                }
                            }
                    }
            }.getOrNull()
            if (bmp != null && card.tag == video) {
                withContext(Dispatchers.Main) {
                    card.mainImage = bmp.toDrawable(card.resources)
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.mainImage = null
        card.tag = null
    }

    private fun decodeDownscaled(path: String, w: Int, h: Int): android.graphics.Bitmap? {
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= max(w, h)) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
