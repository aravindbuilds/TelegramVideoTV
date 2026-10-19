package com.tgtv.videotv.ui

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Renders one video poster card; thumbnail is downloaded lazily through TDLib. */
class VideoCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 400
        private const val CARD_HEIGHT = 225

        /** thumbFileId -> decoded bitmap, shared across recycled rows. */
        private val thumbCache = HashMap<Int, android.graphics.Bitmap>()

        /** TMDB image URL -> decoded bitmap for card backdrops. */
        private val tmdbImageCache = HashMap<String, android.graphics.Bitmap>()
    }

    /** Called when the user long-presses a card. Null = no download handler attached. */
    var onLongPress: ((VideoItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            // Netflix look: image sits flush above a dark info strip
            setInfoAreaBackgroundColor(0xFF141414.toInt())
            mainImageView.scaleType = ImageView.ScaleType.CENTER_CROP
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

        // TMDB enrichment: swap the file name for the real title, add rating
        // and year to the info line, and replace the card image with the
        // official TMDB backdrop when available
        if (TmdbClient.enabled(card.context)) {
            CoroutineScope(Dispatchers.IO).launch {
                val info = runCatching { TmdbClient.lookup(card.context, video.title) }
                    .getOrNull() ?: return@launch
                if (card.tag != video) return@launch
                val backdrop = info.backdropPath?.let { path ->
                    runCatching { loadTmdbImage("https://image.tmdb.org/t/p/w500" + path) }
                        .getOrNull()
                }
                if (card.tag != video) return@launch
                withContext(Dispatchers.Main) {
                    card.titleText = info.title
                    card.contentText = listOf(
                        info.rating?.let { "★%.1f".format(it) },
                        info.year?.toString(),
                        video.prettySize().ifBlank { null },
                        video.prettyDuration().ifBlank { null },
                    ).filterNotNull().joinToString(" • ").ifBlank { video.chatTitle }
                    if (backdrop != null) {
                        card.mainImage = backdrop.toDrawable(card.resources)
                    }
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

    private fun loadTmdbImage(urlStr: String): android.graphics.Bitmap? {
        synchronized(tmdbImageCache) {
            tmdbImageCache[urlStr]?.let { return it }
        }
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return null
            val bmp = BitmapFactory.decodeStream(conn.inputStream) ?: return null
            synchronized(tmdbImageCache) {
                if (tmdbImageCache.size > 300) tmdbImageCache.clear()
                tmdbImageCache[urlStr] = bmp
            }
            return bmp
        } finally {
            conn.disconnect()
        }
    }
}
