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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.videotv.R
import com.tgtv.videotv.model.Release
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.model.Watchlist
import com.tgtv.videotv.svc.DownloadService
import com.tgtv.videotv.store.VideoLibrary
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Hero-style details screen (TMDB Clean Layout): Full backdrop with dark atmospheric overlay,
 * poster on the left, star user rating badge, Resume / Play Again / Bookmark / Remove actions,
 * overview and crew/credits info.
 */
class DetailsActivity : AppCompatActivity() {

    private lateinit var video: VideoItem
    private var items: List<VideoItem> = emptyList()

    private val backdrop: ImageView by lazy { findViewById(R.id.backdrop) }
    private val poster: ImageView by lazy { findViewById(R.id.poster) }
    private val txtTitle: TextView by lazy { findViewById(R.id.txtTitle) }
    private val txtTagline: TextView by lazy { findViewById(R.id.txtTagline) }
    /** Composite user-rating block (star badge + value), reference layout. */
    private val txtRating: LinearLayout by lazy { findViewById(R.id.txtRating) }
    private val txtRatingValue: TextView by lazy { findViewById(R.id.txtRatingValue) }
    private val txtMeta: TextView by lazy { findViewById(R.id.txtMeta) }
    private val txtOverview: TextView by lazy { findViewById(R.id.txtOverview) }
    private val txtOverviewLabel: TextView by lazy { findViewById(R.id.txtOverviewLabel) }
    private val txtCredits: TextView by lazy { findViewById(R.id.txtCredits) }
    private val txtFile: TextView by lazy { findViewById(R.id.txtFile) }
    private val versionsList: LinearLayout by lazy { findViewById(R.id.versionsList) }
    private val txtVersionsLabel: TextView by lazy { findViewById(R.id.txtVersionsLabel) }
    private val btnPlay: Button by lazy { findViewById(R.id.btnPlay) }
    private val btnReplay: Button by lazy { findViewById(R.id.btnReplay) }
    private val btnWatchlist: android.widget.ImageButton by lazy { findViewById(R.id.btnWatchlist) }
    private val btnRemove: android.widget.ImageButton by lazy { findViewById(R.id.btnRemove) }
    private val btnDownload: android.widget.ImageButton by lazy { findViewById(R.id.btnDownload) }
    private val txtQuality: TextView by lazy { findViewById(R.id.txtQuality) }

    /** Set once a TMDB backdrop resolves, so the TG thumb fallback stops racing. */
    private var posterUrl: String? = null
    private var backdropUrl: String? = null

    /** Stored resume position in ms; 0 = never started / watch over from 0. */
    private var resumeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        video = intent.getSerializableExtra(EXTRA_VIDEO) as VideoItem
        @Suppress("UNCHECKED_CAST")
        items = (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<VideoItem>)
            ?: listOf(video)

        // Primary Resume / Play button action
        btnPlay.setOnClickListener { play(video, resumeMs) }

        // Play Again button action (restarts from beginning)
        btnReplay.setOnClickListener { play(video, 0L) }

        // Bookmark (Watchlist) toggle action
        renderWatchlistButton()
        btnWatchlist.setOnClickListener {
            val added = Watchlist.toggle(this, video)
            renderWatchlistState(added)
        }

        // Remove action = hides title locally without deleting from Telegram post
        btnRemove.setOnClickListener {
            VideoLibrary.hide(this, video.chatId, video.messageId)
            Toast.makeText(
                this,
                "Removed from TGTV (stays in Telegram)",
                Toast.LENGTH_SHORT,
            ).show()
            MainBrowseFragment.forceRebuild = true
            finish()
        }

        btnDownload.setOnClickListener { DownloadService.start(this, video) }

        // Hero quality badge (e.g. 1080p, 4K, WEB-DL)
        txtQuality.text = VideoLibrary.qualityLabel(video)
        txtQuality.visibility = View.VISIBLE

        // Focus primary play button for TV D-Pad navigation reliability
        btnPlay.post { btnPlay.requestFocus() }
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
                Log.e("Details", "TMDB render failed", t)
            }
            renderTgBackdrop(video.thumbnailFileId)
        }
    }

    private fun play(v: VideoItem, startMs: Long = 0L) {
        startActivity(PlayerActivity.intent(this, v, startMs))
    }

    /** Re-queries shortly after returning from the player: its onStop save
     *  lands AFTER our onResume (lifecycle order), so poll once more. */
    private val resumeQuery = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        renderResumeState()
        resumeQuery.postDelayed({ renderResumeState() }, 800)
    }

    override fun onPause() {
        resumeQuery.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /**
     * Dynamically handles Resume vs. Play state:
     * - Positions > 20s show "▶ Resume" alongside the "↺ Play Again" button.
     * - Positions near the end (97%+) are auto-cleared.
     */
    private fun renderResumeState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val progress = VideoLibrary.progressOf(this@DetailsActivity, video.chatId, video.messageId)
            var resume = 0L
            if (progress != null) {
                val (posMs, durMs) = progress
                val effDur = if (durMs > 0) durMs else video.durationSeconds * 1000L
                when {
                    posMs <= 20_000L -> Unit
                    effDur > 0L && posMs >= effDur * 97 / 100 -> VideoLibrary.clearProgress(
                        this@DetailsActivity,
                        video.chatId,
                        video.messageId,
                    )
                    else -> resume = posMs
                }
            }
            withContext(Dispatchers.Main) {
                resumeMs = resume
                btnPlay.text = if (resume > 0L) "▶ Resume" else "▶ Play"
                btnReplay.visibility = if (resume > 0L) View.VISIBLE else View.GONE
                btnPlay.requestFocus()
            }
            if (resume > 0L) {
                TdLib.log("details: resume for msg=${video.messageId} at $resume")
            }
        }
    }

    // ------------------------------------------------------------ Content

    private fun renderTmdb(info: TmdbClient.Info) = runOnUiThread {
        try {
            txtTitle.text = info.title
            if (info.tagline != null) {
                txtTagline.text = "“${info.tagline}”"
                txtTagline.visibility = View.VISIBLE
            }

            // User rating block: star badge + "8.1 / 10" + USER RATING
            if (info.rating != null && info.rating > 0.0) {
                txtRatingValue.text = "%.1f".format(info.rating)
                txtRating.visibility = View.VISIBLE
            }

            val meta = listOfNotNull(
                prettyRelease(info.releaseDate),
                info.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                info.runtimeMinutes?.let { m -> "${m / 60}h ${m % 60}m" }
                    ?: video.prettyRuntime().ifBlank { null },
            ).joinToString("  ·  ")

            if (meta.isNotBlank()) {
                txtMeta.text = meta
                txtMeta.visibility = View.VISIBLE
            }
            if (info.overview.isNotBlank()) {
                txtOverviewLabel.visibility = View.VISIBLE
                txtOverview.text = info.overview
            }
            val credits = listOfNotNull(
                info.directors.takeIf { it.isNotEmpty() }
                    ?.let { "Director: ${it.joinToString(", ")}" },
                info.writers.takeIf { it.isNotEmpty() }
                    ?.let { "Screenplay: ${it.joinToString(", ")}" },
                info.starring.takeIf { it.isNotEmpty() }
                    ?.let { "Starring: ${it.joinToString(", ")}" },
            )
            if (credits.isNotEmpty()) {
                txtCredits.text = credits.joinToString("  ·  ")
                txtCredits.visibility = View.VISIBLE
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

    private fun prettyRelease(iso: String?): String? = try {
        iso?.takeIf { it.length >= 10 && it[4] == '-' && it[7] == '-' }?.let {
            "${it.substring(5, 7)}/${it.substring(8, 10)}/${it.substring(0, 4)}"
        }
    } catch (_: Exception) {
        null
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
        btnWatchlist.setImageResource(if (added) R.drawable.ic_check else R.drawable.ic_bookmark)
        btnWatchlist.contentDescription =
            if (added) "In watchlist" else getString(R.string.add_to_watchlist)
    }

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
                    // Prime pill when chosen; glowing dark pill otherwise
                    setBackgroundResource(
                        if (selected) R.drawable.bg_pill_red else R.drawable.bg_pill_list,
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
                if (isPoster) posterUrl = posterUrl
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
            return Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_VIDEO, video as Serializable)
                .putExtra(EXTRA_ITEMS, ArrayList(siblings))
        }
    }
}
