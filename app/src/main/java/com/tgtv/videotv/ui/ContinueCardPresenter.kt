package com.tgtv.videotv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.leanback.widget.Presenter
import com.tgtv.videotv.R
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/**
 * Renders one "Continue Watching" card: 16:9 art, a thin red progress bar and
 * a time-remaining line. Selecting it streams on from the stored position
 * (the click handler in [MainBrowseFragment] passes the position along);
 * long-press opens the same menu as every other card.
 */
class ContinueCardPresenter : Presenter() {

    /** Progress for the item being bound: (positionMs, durationMs). */
    var progressProvider: ((VideoItem) -> Pair<Long, Long>?)? = null

    /** Called when the user long-presses a card. */
    var onLongPress: ((VideoItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_card, parent, false) as ViewGroup
        val glow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 6f * parent.context.resources.displayMetrics.density
            setStroke(
                (3f * parent.context.resources.displayMetrics.density).toInt(),
                0xFFE50914.toInt(),
            )
        }
        view.foreground = glow
        view.foreground.alpha = 0
        view.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.07f else 1f)
                .scaleY(if (focused) 1.07f else 1f)
                .setDuration(150)
                .start()
            glow.setAlpha(if (focused) 255 else 0)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = viewHolder.view as ViewGroup
        val video = item as VideoItem
        card.tag = video
        card.setOnLongClickListener { v ->
            (v.tag as? VideoItem)?.let { onLongPress?.invoke(it) }
            true
        }

        val title = card.findViewById<TextView>(R.id.continueTitle)
        val bar = card.findViewById<ProgressBar>(R.id.continueProgress)
        val meta = card.findViewById<TextView>(R.id.continueMeta)
        val art = card.findViewById<ImageView>(R.id.continueArt)

        val progress = progressProvider?.invoke(video)
        val posMs = progress?.first ?: 0L
        val durMs = progress?.second ?: 0L
        title.text = video.title
        when {
            durMs > 0L && posMs >= durMs - 60_000L -> meta.text = "Almost done"
            durMs > 0L -> {
                val minLeft = ceil((durMs - posMs) / 60_000.0).toInt()
                meta.text = if (minLeft <= 1) "Less than a minute left" else "$minLeft min left"
            }
            else -> meta.text = "In progress — ${video.prettyDuration()}"
        }
        if (durMs > 0L) {
            bar.progress = ((posMs * 1000L) / durMs).toInt().coerceIn(0, 1000)
            bar.visibility = ProgressBar.VISIBLE
        } else {
            bar.progress = 0
        }

        // Art: Telegram thumbnail first (it IS the frame where they stopped
        // watching), TMDB poster as fallback. The same lookup also upgrades
        // the title from the raw file name to "Show · Sxx Eyy".
        // The Telegram attempt is time-boxed: the index stores file ids from
        // a previous app process, and TDLib never answers a DownloadFile for
        // a dead id - without the timeout the card would show no art at all.
        art.setImageDrawable(null)
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = withTimeoutOrNull(8_000L) {
                VideoCardPresenter.loadThumb(video, 480, 270)
            }
            if (bmp == null) {
                TdLib.log("continue: no thumb for '${video.title}' (${video.thumbnailFileId}) - falling back to TMDB")
            }
            var resolvedTitle: String? = null
            val poster = bmp ?: runCatching {
                TmdbClient.lookupQuick(art.context, video.title)
            }.getOrNull()?.also { resolvedTitle = it.title }?.posterPath?.let { path ->
                runCatching {
                    VideoCardPresenter.loadTmdbImage(
                        "https://image.tmdb.org/t/p/w300$path",
                    )
                }.getOrNull()
            }
            if (poster != null) {
                TdLib.log("continue: art ready via ${bmp != null} for '${video.title}'")
            } else {
                TdLib.log("continue: NO art for '${video.title}'")
            }
            withContext(Dispatchers.Main) {
                // "Show Name · S01 E02" beats the raw release name
                val ep = TmdbClient.parseEpisode(video.fileName)
                resolvedTitle?.let { t ->
                    title.text = if (ep.isNullOrBlank()) t else "$t  ·  $ep"
                }
                if (poster != null && card.tag == video) {
                    art.setImageBitmap(poster)
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ViewGroup
        card.tag = null
        card.findViewById<ImageView>(R.id.continueArt).setImageDrawable(null)
        card.findViewById<ProgressBar>(R.id.continueProgress).progress = 0
    }
}
