package com.tgtv.videotv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.videotv.R
import com.tgtv.videotv.model.Release
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.model.Watchlist
import com.tgtv.videotv.svc.DownloadService
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hero-style details screen (Zootopia reference layout): full TMDB backdrop,
 * poster on the left, ringed rating, Play / Watchlist / Download buttons,
 * overview and genre line, plus a "Versions" list to pick between alternate
 * files of the same title (1080p vs 720p, language cuts, ...).
 */
class DetailsActivity : AppCompatActivity() {

    private lateinit var video: VideoItem
    private var items: List<VideoItem> = emptyList()

    private val backdrop: ImageView by lazy { findViewById(R.id.backdrop) }
    private val poster: ImageView by lazy { findViewById(R.id.poster) }
    private val txtTitle: TextView by lazy { findViewById(R.id.txtTitle) }
    private val txtTagline: TextView by lazy { findViewById(R.id.txtTagline) }
    private val txtRating: TextView by lazy { findViewById(R.id.txtRating) }
    private val txtStatus: TextView by lazy { findViewById(R.id.txtStatus) }
    private val txtOverview: TextView by lazy { findViewById(R.id.txtOverview) }
    private val txtGenres: TextView by lazy { findViewById(R.id.txtGenres) }
    private val txtFile: TextView by lazy { findViewById(R.id.txtFile) }
    private val versionsList: LinearLayout by lazy { findViewById(R.id.versionsList) }
    private val txtVersionsLabel: TextView by lazy { findViewById(R.id.txtVersionsLabel) }
    private val btnPlay: Button by lazy { findViewById(R.id.btnPlay) }
    private val btnDownload: Button by lazy { findViewById(R.id.btnDownload) }
    private val btnWatchlist: Button by lazy { findViewById(R.id.btnWatchlist) }

    /** Set once a TMDB backdrop resolves, so the TG thumb fallback stops racing. */
    private var posterUrl: String? = null
    private var backdropUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        video = intent.getSerializableExtra(EXTRA_VIDEO) as VideoItem
        @Suppress("UNCHECKED_CAST")
        items = (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<VideoItem>)
            ?: listOf(video)

        btnPlay.setOnClickListener { play(video) }
        btnDownload.setOnClickListener { DownloadService.start(this, video) }
        renderWatchlistButton()
        btnWatchlist.setOnClickListener {
            val added = Watchlist.toggle(this, video)
            renderWatchlistState(added)
        }
        renderFileLine()
        renderVersions()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!TmdbClient.enabled(applicationContext)) return@launch
                val info = TmdbClient.lookup(applicationContext, video.title)
                    ?.let { base -> TmdbClient.enrich(applicationContext, base) }
                    ?: return@launch
                renderTmdb(info)
            } catch (t: Throwable) {
                Log.e("Details", "tmdb render failed", t)
            }
            renderTgBackdrop(video.thumbnailFileId)
        }
    }

    private fun play(v: VideoItem) {
        PlayerActivity.intent(this, v).let { startActivity(it) }
    }

    /** IMDb-style graded score: 80+ dark green, 70 light green,
     *  60 yellow, 50 orange, below red. */
    private fun gradeColor(pct: Int): Int = when {
        pct >= 80 -> 0xFF1E8449.toInt()
        pct >= 70 -> 0xFF8BC34A.toInt()
        pct >= 60 -> 0xFFF5C518.toInt()
        pct >= 50 -> 0xFFEF6C00.toInt()
        else -> 0xFFE50914.toInt()
    }

    // ------------------------------------------------------------ Content

    private fun renderTmdb(info: TmdbClient.Info) = runOnUiThread {
        try {
            txtTitle.text =
                info.title + (info.year?.let { " ($it)" } ?: "")
            if (info.tagline != null) {
                txtTagline.text = info.tagline
                txtTagline.visibility = View.VISIBLE
            }
            if (info.rating != null && info.rating > 0.0) {
                val pct = (info.rating * 10).roundToInt()
                val grade = gradeColor(pct)
                txtRating.text = "$pct%"
                txtRating.setTextColor(grade)
                txtRating.background?.setTint(grade)
                txtRating.visibility = View.VISIBLE
            }
            txtStatus.text = listOfNotNull(
                info.runtimeMinutes?.let { m -> "${m / 60}h ${m % 60}m" }
                    ?: video.prettyRuntime().ifBlank { null },
                info.status?.let { "Status - $it" },
            ).joinToString("   ·   ")
            if (info.overview.isNotBlank()) {
                txtOverview.text = info.overview
            }
            if (info.genres.isNotEmpty()) {
                txtGenres.text = "Genre: " + info.genres.joinToString(", ")
                txtGenres.visibility = View.VISIBLE
            }
            info.backdropPath?.let { path ->
                backdropUrl = IMAGE_BASE_W1280 + path
                lifecycleScope.launch(Dispatchers.IO) {
                    loadInto(backdrop, backdropUrl ?: return@launch, isPoster = false)
                }
            }
            info.posterPath?.let { path ->
                posterUrl = IMAGE_BASE_W342 + path
                lifecycleScope.launch(Dispatchers.IO) {
                    loadInto(poster, posterUrl ?: return@launch, isPoster = true)
                }
            }
        } catch (t: Throwable) {
            Log.e("Details", "render failed", t)
        }
    }

    private fun renderFileLine() {
        val parts = listOfNotNull(
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
            Release.describe(video.fileName).takeIf { it.isNotBlank() },
            "from ${video.chatTitle}",
        )
        txtFile.text = parts.joinToString("   ·   ")
    }

    private fun renderWatchlistButton() =
        renderWatchlistState(Watchlist.contains(this, video))

    private fun renderWatchlistState(added: Boolean) {
        btnWatchlist.text = if (added) "✓  In Watchlist" else "Add to Watchlist"
        btnWatchlist.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (added) 0xFFE50914.toInt() else 0x33FFFFFF,
        )
    }

    /** Every file in the row with the same parsed title is an alternate version. */
    private fun renderVersions() {
        try {
            val key = TmdbClient.parseTitle(video.fileName.ifBlank { video.title }).lowercase()
            val versions = items
                .filter {
                    TmdbClient.parseTitle(it.fileName.ifBlank { it.title }).lowercase() == key
                }
                .sortedWith(
                    compareByDescending<VideoItem> { Release.qualityRank(it.fileName) }
                        .thenByDescending { it.fileSize },
                )

            if (versions.size <= 1) {
                txtVersionsLabel.visibility = View.GONE
                versionsList.visibility = View.GONE
                return
            }

            txtVersionsLabel.text = "Versions (${versions.size})"
            versionsList.removeAllViews()
            for (v in versions) {
                val row = Button(this).apply {
                    isAllCaps = false
                    textSize = 14f
                    minWidth = 900
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    val selected = v.fileId == video.fileId && v.messageId == video.messageId
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (selected) 0xFFE50914.toInt() else 0xFF262626.toInt(),
                    )
                    setTextColor(0xFFFFFFFF.toInt())
                    text = listOfNotNull(
                        Release.quality(v.fileName) ?: "Quality unknown",
                        v.prettySize().ifBlank { null },
                        Release.audio(v.fileName),
                        Release.source(v.fileName),
                    ).joinToString("   ·   ")
                    setOnClickListener { play(v) }
                }
                versionsList.addView(row)
            }
        } catch (t: Throwable) {
            Log.e("Details", "versions render failed", t)
        }
    }

    // ----------------------------------------------------------- Images

    /** TG thumbnail fallback for both the poster and the backdrop if TMDB misses. */
    private suspend fun renderTgBackdrop(thumbnailFileId: Int) {
        if (thumbnailFileId == 0) return
        val path = runCatching {
            TdLib.downloadFully(thumbnailFileId, priority = 1).local?.path
        }.getOrNull() ?: return
        withContext(Dispatchers.Main) {
            if (backdropUrl == null) {
                decode(path, wide = true)?.let { backdrop.setImageBitmap(it) }
            }
            if (posterUrl == null) {
                decode(path, wide = false)?.let { poster.setImageBitmap(it) }
            }
        }
    }

    private suspend fun loadInto(view: ImageView, url: String, isPoster: Boolean) {
        val bmp = runCatching { fetchBitmap(url) }.getOrNull() ?: return
        withContext(Dispatchers.Main) {
            val stale = if (isPoster) posterUrl != url else backdropUrl != url
            if (view.tag != null) return@withContext
            if (!stale) {
                if (isPoster) posterUrl = posterUrl // keep guard semantics
                view.setImageBitmap(bmp)
                view.tag = url
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            return if (c.responseCode == 200) {
                BitmapFactory.decodeStream(c.inputStream)
            } else {
                null
            }
        } finally {
            c.disconnect()
        }
    }

    private fun decode(path: String, wide: Boolean): Bitmap? {
        val f = java.io.File(path)
        if (!f.exists() || f.length() == 0L) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        // Center-crop-ish fill for the target aspect (backdrop ~16:9, poster 2:3-ish)
        return if (wide) {
            Bitmap.createScaledBitmap(bmp, 1280, 760, true)
        } else {
            Bitmap.createScaledBitmap(bmp, 512, 760, true)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val IMAGE_BASE_W1280 = "https://image.tmdb.org/t/p/w1280"
        private const val IMAGE_BASE_W342 = "https://image.tmdb.org/t/p/w342"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_ITEMS = "items"

        fun intent(context: Context, video: VideoItem, siblings: List<VideoItem>): Intent {
            val i = Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_VIDEO, video as Serializable)
                .putExtra(EXTRA_ITEMS, ArrayList(siblings))
            return i
        }
    }
}
