package com.tgtv.videotv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tgtv.videotv.R
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.sub.Languages
import com.tgtv.videotv.sub.SubtitlesClient
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen TV player that streams the Telegram file through [TdVideoStreamDataSource]. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context, video: VideoItem): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, video.fileId)
                putExtra(EXTRA_SIZE, video.fileSize)
                putExtra(EXTRA_MIME, video.mimeType)
                putExtra(EXTRA_NAME, video.fileName.ifBlank { video.title })
            }

        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "name"
    }

    private var player: ExoPlayer? = null
    private var currentFileId = 0
    private var fileName = ""
    private var externalSubsTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)
        fileName = intent.getStringExtra(EXTRA_NAME) ?: ""
        currentFileId = fileId

        if (fileId <= 0) {
            Toast.makeText(this, "Invalid video reference", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        TdLib.activeStreamFileId = fileId
        // Fresh DataSource per load: ExoPlayer opens several DataSources
        // concurrently (e.g. MP4 reads the moov trailer at EOF while the main
        // loader reads the start) and they must not share mutable state.
        val dataSourceFactory: DataSource.Factory =
            DataSource.Factory { TdVideoStreamDataSource(fileId, size) }

        val errorView = findViewById<TextView>(R.id.playerError)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // YouTube-style time-based buffering: start play as soon as ~2s is
        // buffered, then keep looking ahead up to 5 minutes of video.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 300_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setBackBuffer(60_000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Mime type left unset: media3 sniffs the container (mp4/mkv/webm...)
                setMediaItem(MediaItem.fromUri(Uri.parse("tgvfile://$fileId/$size")))
                // Subtitles setting drives both embedded selection and web fetch
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setSelectUndeterminedTextLanguage(Credentials.subsEnabled(this@PlayerActivity))
                    .build()
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errorView.visibility = View.VISIBLE
                        errorView.text = "Playback error: ${error.errorCodeName}. " +
                            "Try the long-press download instead."
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        maybeFetchExternalSubtitles(tracks)
                    }
                })
            }
        player = exo
        findViewById<PlayerView>(R.id.playerView).player = exo
    }

    /**
     * When the file ships no subtitle tracks at all and web subtitles are
     * enabled, resolves the title through [TmdbClient] and side-loads an
     * OpenSubtitles file (bring-your-own API key).
     */
    private fun maybeFetchExternalSubtitles(tracks: Tracks) {
        if (externalSubsTried) return
        if (!Credentials.subsEnabled(this)) return
        if (fileName.isBlank()) return
        val hasText = tracks.groups.any {
            it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0)
        }
        if (hasText) return
        externalSubsTried = true
        val exo = player ?: return
        val context = applicationContext
        if (!SubtitlesClient.hasKey(context)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val fetched = SubtitlesClient.fetchFor(context, VideoItem(
                messageId = 0L,
                chatTitle = "",
                title = fileName,
                fileId = currentFileId,
                fileSize = 0L,
                durationSeconds = 0,
                mimeType = "",
                fileName = fileName,
                thumbnailFileId = 0,
            )) ?: run {
                TdLib.log("subs: no external subtitles found for '$fileName'")
                return@launch
            }
            withContext(Dispatchers.Main) {
                attachSubtitle(exo, fetched.file, fetched.language, fetched.label)
            }
        }
    }

    /** Re-opens the media item with the fetched subtitle attached side-loaded. */
    private fun attachSubtitle(exo: ExoPlayer, file: File, language: String, label: String) {
        val position = exo.currentPosition
        val playWhenReady = exo.playWhenReady
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setLabel(Languages.nameOf(language))
            .setId("tgsubs-$language")
            .build()
        val base = exo.currentMediaItem ?: return
        val item = base.buildUpon()
            .setSubtitleConfigurations(listOf(config))
            .build()
        exo.setMediaItem(item, position)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        if (currentFileId > 0) {
            if (TdLib.activeStreamFileId == currentFileId) {
                TdLib.activeStreamFileId = null
            }
            TdLib.scheduleStreamCachePurge(currentFileId)
        }
    }
}
