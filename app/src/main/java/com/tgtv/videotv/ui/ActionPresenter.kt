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
