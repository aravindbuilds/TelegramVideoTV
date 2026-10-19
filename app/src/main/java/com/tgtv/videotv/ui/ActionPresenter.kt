package com.tgtv.videotv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.tgtv.videotv.R

data class ActionItem(val id: Int, val icon: String, val label: String, val sub: String)

/** Renders one icon-tile card for the Settings row at the bottom of the library. */
class ActionPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_action_card, parent, false)
        // Match the video cards: lift + red glow ring while focused
        val d = v.resources.displayMetrics.density
        val glow = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 12f * d
            setStroke((3f * d).toInt(), 0xFFE50914.toInt())
        }
        v.foreground = glow
        glow.alpha = 0
        v.onFocusChangeListener = android.view.View.OnFocusChangeListener { view, focused ->
            view.animate()
                .scaleX(if (focused) 1.07f else 1f)
                .scaleY(if (focused) 1.07f else 1f)
                .setDuration(150)
                .start()
            (view.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (focused) 0xFF3A3A3A.toInt() else 0xFF242424.toInt())
            glow.alpha = if (focused) 255 else 0
        }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val a = item as ActionItem
        viewHolder.view.findViewById<TextView>(R.id.icon).text = a.icon
        viewHolder.view.findViewById<TextView>(R.id.label).text = a.label
        viewHolder.view.findViewById<TextView>(R.id.sub).text = a.sub
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
