package com.tgtv.videotv.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.tgtv.videotv.R

class MainActivity : FragmentActivity() {

    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MainBrowseFragment())
                .commit()
        }
        Toast.makeText(this, getString(R.string.download_hint), Toast.LENGTH_LONG).show()
    }

    /** Fragment reports loading / connection / error or edit messages on its own row. */
    fun setStatus(text: String?) {
        statusView?.let { v ->
            v.visibility = if (text == null) View.GONE else View.VISIBLE
            v.text = text.orEmpty()
        }
    }
}
