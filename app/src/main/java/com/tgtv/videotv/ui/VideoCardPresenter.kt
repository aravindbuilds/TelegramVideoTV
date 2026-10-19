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
        // Portrait 2:3 poster card (Netflix style) - most files have no wide
        // thumbnail but TMDB posters are nearly always available
        private const val CARD_WIDTH = 300
        private const val CARD_HEIGHT = 450

        /** thumbFileId -> decoded bitmap, shared across recycled rows. */
        private val thumbCache = HashMap<Int, android.graphics.Bitmap>()

        /** TMDB image URL -> decoded bitmap for card backdrops. */
        private val tmdbImageCache = HashMap<String, android.graphics.Bitmap>()

        /** Which card views already got their TMDB poster (thumb must not overwrite). */
        private val tmdbApplied = java.util.WeakHashMap<View, Boolean>()
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
        // Noticeable selection treatment: card lifts slightly and a red glow
        // ring lights up around the whole card while focused
        val glow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 6f * parent.context.resources.displayMetrics.density
            setStroke(
                (3f * parent.context.resources.displayMetrics.density).toInt(),
                0xFFE50914.toInt(),
            )
        }
        card.foreground = glow
        card.foreground.alpha = 0

        return ViewHolder(card).apply {
            view.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, focused ->
                v.animate()
                    .scaleX(if (focused) 1.07f else 1f)
                    .scaleY(if (focused) 1.07f else 1f)
                    .setDuration(150)
                    .start()
                glow.setAlpha(if (focused) 255 else 0)
            }
        }
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
        synchronized(tmdbApplied) { tmdbApplied.put(card, false) }

        // TMDB enrichment runs for EVERY card (before the thumbnail early
        // return) - cards without a Telegram thumbnail otherwise never get
        // their poster. Poster first; backdrop as fallback.
        if (TmdbClient.enabled(card.context)) {
            CoroutineScope(Dispatchers.IO).launch {
                val info = runCatching { TmdbClient.lookup(card.context, video.title) }
                    .getOrNull() ?: return@launch
                if (card.tag != video) return@launch
                val poster = info.posterPath?.let { path ->
                    runCatching { loadTmdbImage("https://image.tmdb.org/t/p/w300" + path) }
                        .getOrNull()
                }
                val backdrop = if (poster == null) {
                    info.backdropPath?.let { path ->
                        runCatching { loadTmdbImage("https://image.tmdb.org/t/p/w500" + path) }
                            .getOrNull()
                    }
                } else null
                if (card.tag != video) return@launch
                withContext(Dispatchers.Main) {
                    card.titleText = info.title
                    card.contentText = listOf(
                        info.rating?.let { "★%.1f".format(it) },
                        info.year?.toString(),
                        video.prettySize().ifBlank { null },
                        video.prettyDuration().ifBlank { null },
                    ).filterNotNull().joinToString(" • ").ifBlank { video.chatTitle }
                    (poster ?: backdrop)?.let { img ->
                        synchronized(tmdbApplied) { tmdbApplied.put(card, true) }
                        card.mainImage = img.toDrawable(card.resources)
                    }
                }
            }
        }

        if (video.thumbnailFileId == 0) return

        synchronized(thumbCache) {
            thumbCache[video.thumbnailFileId]?.let { cached ->
                synchronized(tmdbApplied) {
                    if (tmdbApplied[card] != true) {
                        card.mainImage = cached.toDrawable(card.resources)
                    }
                }
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
                    synchronized(tmdbApplied) {
                        if (tmdbApplied[card] != true) {
                            card.mainImage = bmp.toDrawable(card.resources)
                        }
                    }
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.mainImage = null
        card.tag = null
        synchronized(tmdbApplied) { tmdbApplied.remove(card) }
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
