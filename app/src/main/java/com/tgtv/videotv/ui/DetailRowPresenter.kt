package com.tgtv.videotv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.tgtv.videotv.R
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.store.VideoLibrary

/**
 * Renders one "Detailed" library row: the raw file name on line one and
 * chat / size / duration / parsed quality on line two — the phone app's
 * "Details list" view style, for people who want to read what they pick.
 */
class DetailRowPresenter : Presenter() {

    /** Called when the user long-presses a row. */
    var onLongPress: ((VideoItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_detail_row, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isLongClickable = true
        // Selection treatment: red left bar + slight lift, matching the
        // app's red glow language without a poster-sized zoom
        val glow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 4f * parent.context.resources.displayMetrics.density
            setStroke(
                (3f * parent.context.resources.displayMetrics.density).toInt(),
                0xFFE50914.toInt(),
            )
        }
        view.foreground = glow
        view.foreground.alpha = 0
        view.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, focused ->
            v.animate()
                .scaleX(if (focused) 1.02f else 1f)
                .scaleY(if (focused) 1.02f else 1f)
                .setDuration(120)
                .start()
            glow.setAlpha(if (focused) 255 else 0)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val view = viewHolder.view
        val video = item as VideoItem
        view.tag = video
        view.setOnLongClickListener { v ->
            (v.tag as? VideoItem)?.let { onLongPress?.invoke(it) }
            true
        }
        view.findViewById<TextView>(R.id.detailTitle).text =
            video.fileName.ifBlank { video.title }
        view.findViewById<TextView>(R.id.detailSub).text = listOf(
            video.chatTitle.ifBlank { null },
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
            VideoLibrary.qualityLabel(video).ifBlank { null },
        ).filterNotNull().joinToString("  •  ")
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.tag = null
    }
}
