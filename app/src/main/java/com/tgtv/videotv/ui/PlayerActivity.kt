package com.tgtv.videotv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tgtv.videotv.R
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.model.VideoMapper
import com.tgtv.videotv.sub.Languages
import com.tgtv.videotv.sub.SubtitlesClient
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.td.TmdbClient
import com.tgtv.videotv.store.VideoLibrary
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen TV player that streams the Telegram file through [TdVideoStreamDataSource]. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context, video: VideoItem, startMs: Long = 0L): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, video.fileId)
                putExtra(EXTRA_SIZE, video.fileSize)
                putExtra(EXTRA_MIME, video.mimeType)
                putExtra(EXTRA_NAME, video.fileName.ifBlank { video.title })
                putExtra(EXTRA_REMOTE, video.remoteFileId)
                putExtra(EXTRA_KIND, video.fileKind)
                putExtra(EXTRA_CHAT_ID, video.chatId)
                putExtra(EXTRA_MSG_ID, video.messageId)
                putExtra(EXTRA_START_MS, startMs)
            }

        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_REMOTE = "remoteId"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_MSG_ID = "msgId"
        private const val EXTRA_START_MS = "startMs"
    }

    private var player: ExoPlayer? = null
    private var currentFileId = 0
    private var fileName = ""
    private var externalSubsTried = false
    /** Which library row is playing - the resume position keys off it. */
    private var chatId = 0L
    private var msgId = 0L
    private var startPosMs = 0L
    private var progressSaved = false
    private var qualityIdx = 0
    private val qualityLabels = arrayOf("Auto", "1080p", "720p", "480p")
    private val qualityCaps = arrayOf(
        null,                       // Auto: no constraint
        Pair(1920, 1080),
        Pair(1280, 720),
        Pair(854, 480),
    )
    /** Subtitles toggle - defaults to the library-wide setting. */
    private var subsOn = true
    private var menuLongHandled = false
    private var speedIdx = 1
    private var audioStep = 0

    /** Full-screen subtitle view (moved out of the video aspect frame). */
    private var cuesView: androidx.media3.ui.SubtitleView? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Remote nice-to-have:
        //   tap MENU / INFO        -> opens the in-player Settings panel
        //                             (subtitles / audio / speed / quality)
        //   hold MENU / INFO       -> subtitles on/off direct toggle
        return if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
            keyCode == android.view.KeyEvent.KEYCODE_INFO
        ) {
            if ((event?.repeatCount ?: 0) > 0) {
                if (!menuLongHandled) {
                    menuLongHandled = true
                    toggleSubtitles()
                }
            }
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean =
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
            keyCode == android.view.KeyEvent.KEYCODE_INFO
        ) {
            if (!menuLongHandled) showSettingsPanel()
            menuLongHandled = false
            true
        } else {
            super.onKeyUp(keyCode, event)
        }

    /**
     * In-player settings: one dialog with everything the user needs mid-play.
     * Subtitles (embedded + side-loaded), audio track, playback speed and
     * resolution cap - selection is applied right away and the list re-opens
     * so the next press acts on fresh state. Back dismisses.
     */
    private fun showSettingsPanel() {
        val exo = player ?: return
        val sizeIdx = Credentials.subsTextSize(this)
        val items = listOf(
            "Subtitles: ${if (subsOn) "On" else "Off"}",
            "Subtitle size: ${subsSizeLabel(sizeIdx)}",
            "Add subtitles (OpenSubtitles)",
            audioLabel(exo),
            "Playback speed: ${subsSpeedPreset()}",
            "Quality: ${qualityLabels[qualityIdx]}",
            "Close",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Playback settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> toggleSubtitles()
                    1 -> applySubtitleSize((sizeIdx + 1) % subsSizeLabels.size)
                    2 -> fetchSubtitlesManually()
                    3 -> cycleAudioTrack(exo)
                    4 -> cycleSpeed(exo)
                    5 -> applyQuality((qualityIdx + 1) % qualityLabels.size)
                    else -> return@setItems
                }
                // Refresh list to show new state for the next selection
                showSettingsPanel()
            }
            .show()
    }

    /** Subtitle text size profiles, indices persisted in Credentials.subsTextSize. */
    private val subsSizeLabels = listOf("Small", "Medium", "Large")

    private fun subsSizeLabel(idx: Int): String =
        subsSizeLabels.getOrElse(idx) { subsSizeLabels[0] }

    private fun subsSizeFraction(idx: Int): Float = when (idx) {
        1 -> 0.042f
        2 -> 0.0533f
        else -> 0.033f
    }

    private fun applySubtitleSize(idx: Int) {
        val stored = idx.coerceIn(0, subsSizeLabels.size - 1)
        Credentials.setSubsTextSize(this, stored)
        cuesView?.setFractionalTextSize(subsSizeFraction(stored))
    }

    private fun audioLabel(exo: ExoPlayer): String {
        val groups = exo.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isTrackSupported(0) }
        var idx = 0
        var selectedName = "Auto"
        for (g in groups) {
            val len = g.mediaTrackGroup?.length ?: 0
            for (i in 0 until len) {
                if (g.isTrackSelected(i)) {
                    val f = g.getTrackFormat(i)
                    selectedName = when {
                        f.language?.isNotBlank() == true ->
                            Languages.nameOf(f.language!!) +
                                (if (f.label?.isNotBlank() == true) " (${f.label})" else "")
                        else -> f.label ?: "Track ${idx + 1}"
                    }
                }
                idx++
            }
        }
        return "Audio: $selectedName"
    }

    /** Cycles the user across every available audio track (incl. back to auto). */
    private fun cycleAudioTrack(exo: ExoPlayer) {
        val groups = exo.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isTrackSupported(0) }
        val options = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()
        groups.forEach { g ->
            val len = g.mediaTrackGroup?.length ?: 0
            for (i in 0 until len) options.add(g to i)
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "No audio track options", Toast.LENGTH_SHORT).show()
            return
        }
        // options.size + 1 states: cycling ends with "Auto" back at the start
        val steps = options.size + 1
        val next = (audioStep + 1) % steps
        audioStep = next
        if (next == 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            Toast.makeText(this, "Audio: Auto", Toast.LENGTH_SHORT).show()
        } else {
            val (g, i) = options[next - 1]
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        g.mediaTrackGroup!!,
                        i,
                    ),
                )
                .build()
            val f = g.getTrackFormat(i)
            val name = when {
                f.language?.isNotBlank() == true ->
                    Languages.nameOf(f.language!!) +
                        (if (f.label?.isNotBlank() == true) " (${f.label})" else "")
                else -> f.label ?: "Track $next"
            }
            Toast.makeText(this, "Audio: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private val speedPresets = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    private fun subsSpeedPreset() = "${speedPresets[speedIdx]}x"

    private fun cycleSpeed(exo: ExoPlayer) {
        speedIdx = (speedIdx + 1) % speedPresets.size
        exo.setPlaybackSpeed(speedPresets[speedIdx])
        Toast.makeText(this, "Speed: ${speedPresets[speedIdx]}x", Toast.LENGTH_SHORT).show()
    }

    /** Turns all subtitle tracks (embedded + side-loaded) on or off. */
    private fun toggleSubtitles() {
        val exo = player ?: return
        subsOn = !subsOn
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .build()
        Toast.makeText(this, if (subsOn) "Subtitles: on" else "Subtitles: off", Toast.LENGTH_SHORT)
            .show()
    }

    private fun cycleQuality() {
        applyQuality((qualityIdx + 1) % qualityLabels.size)
    }

    private fun applyQuality(idx: Int) {
        val exo = player ?: return
        val cap = qualityCaps[idx]
        if (cap != null) {
            // A cap below every available video track would deselect video
            // entirely (black screen), so refuse that step proactively.
            val anyFits = exo.currentTracks.groups.any { g ->
                if (g.type != C.TRACK_TYPE_VIDEO) return@any false
                val len = g.mediaTrackGroup?.length ?: return@any false
                (0 until len).any { i ->
                    val f = g.getTrackFormat(i)
                    f.width > 0 && f.height > 0 && f.width <= cap.first && f.height <= cap.second
                }
            }
            if (!anyFits) {
                Toast.makeText(
                    this,
                    "No ${qualityLabels[idx]} stream in this video - keeping ${qualityLabels[qualityIdx]}",
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
        }
        qualityIdx = idx
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
            if (cap == null) clearVideoSizeConstraints()
            else setMaxVideoSize(cap.first, cap.second)
        }.build()
        Toast.makeText(this, "Quality: ${qualityLabels[idx]}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        var fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
        var size = intent.getLongExtra(EXTRA_SIZE, 0L)
        fileName = intent.getStringExtra(EXTRA_NAME) ?: ""
        val remoteId = intent.getStringExtra(EXTRA_REMOTE) ?: ""
        val kind = intent.getStringExtra(EXTRA_KIND) ?: ""
        val chatIdExtra = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val msgIdExtra = intent.getLongExtra(EXTRA_MSG_ID, 0L)
        startPosMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
        chatId = chatIdExtra
        msgId = msgIdExtra

        lifecycleScope.launch {
            // DB-restored rows carry a session-stale file id ("file
            // unspecified"). The stable remote id re-resolves to a live file
            // in this session; strictly better when it works, else fall back.
            if (remoteId.isNotBlank()) {
                val fresh = withContext(Dispatchers.IO) {
                    runCatching {
                        TdLib.send(
                            TdApi.GetRemoteFile(
                                remoteId,
                                fileTypeFor(kind, fileName),
                            ),
                        ) as? TdApi.File
                    }.getOrNull()
                }
                if (fresh != null && fresh.id > 0) {
                    fileId = fresh.id
                    if (fresh.size > 0) size = fresh.size
                } else {
                    TdLib.log("player: remote re-resolve failed, using stored fileId=$fileId")
                }
            }

            // Last resort for rows with no remote id: bots cannot scan
            // history, but fetching one known message by id usually still works.
            if (chatId != 0L && msgId != 0L) {
                val msg = withContext(Dispatchers.IO) {
                    runCatching {
                        TdLib.send(TdApi.GetMessage(chatId, msgId)) as? TdApi.Message
                    }.getOrNull()
                }
                if (msg != null) {
                    VideoMapper.mapMessage(msg, "")?.let { v ->
                        fileId = v.fileId
                        if (v.fileSize > 0) size = v.fileSize
                    }
                } else {
                    TdLib.log("player: GetMessage fallback failed for $msgId")
                }
            }

            if (fileId <= 0) {
                Toast.makeText(this@PlayerActivity, "Invalid video reference", Toast.LENGTH_SHORT)
                    .show()
                finish()
                return@launch
            }
            currentFileId = fileId
            startPlayback(fileId, size)
        }
    }

    /** Maps a stored kind (or mime sniff) to a TDLib FileType for re-resolve. */
    private fun fileTypeFor(kind: String, name: String): TdApi.FileType = when {
        kind == "videoNote" -> TdApi.FileTypeVideoNote()
        kind == "animation" -> TdApi.FileTypeAnimation()
        kind == "document" -> TdApi.FileTypeDocument()
        else -> TdApi.FileTypeVideo()
    }

    private fun startPlayback(fileId: Int, size: Long) {
        currentFileId = fileId
        TdLib.activeStreamFileId = fileId
        // Fresh DataSource per load: ExoPlayer opens several DataSources
        // concurrently (e.g. MP4 reads the moov trailer at EOF while the main
        // loader reads the start) and they must not share mutable state.
        val dataSourceFactory: DataSource.Factory =
            DataSource.Factory { TdVideoStreamDataSource(fileId, size) }

        val errorView = findViewById<TextView>(R.id.playerError)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        // Playback settings reachable from the visible controls too - the
        // gear button rides along with the controller's show/hide state.
        val settingsBtn = findViewById<android.widget.Button>(R.id.playerSettingsBtn)
        settingsBtn.setOnClickListener { showSettingsPanel() }
        playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    settingsBtn.visibility =
                        if (visibility == android.view.View.VISIBLE) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            },
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Subtitles must key off the TV screen, not the letterboxed video:
        // PlayerView hosts the subtitle view inside AspectRatioFrameLayout,
        // so its bottom fraction rides on the video's bounds. Detach the
        // SubtitleView and hook it to the root FrameLayout (full screen) and
        // feed cues ourselves; the fraction then measures the real display.
        val rootLayout = playerView.parent as android.view.ViewGroup
        playerView.subtitleView?.let { sub ->
            // Lift cues a little above the bottom edge of the TV screen
            sub.setBottomPaddingFraction(0.05f)
            // Canvas mode paints relative to the view bounds (bottom + padding
            // fraction); the default WEB mode stacks TextViews from the top.
            sub.setViewType(androidx.media3.ui.SubtitleView.VIEW_TYPE_CANVAS)
            // Size handled full-screen: use the persisted Small/Medium/Large
            // profile (Small keeps the old letterboxed-scale look).
            sub.setFractionalTextSize(subsSizeFraction(Credentials.subsTextSize(this)))
            (sub.parent as? android.view.ViewGroup)?.removeView(sub)
            rootLayout.addView(
                sub,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    // Anchor cues to the bottom edge of the TV screen
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
                ),
            )
            cuesView = sub
        }

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
                // Hold a partial wake lock while audio is rendering: without it
                // Android TV boxes go into their idle sleep a couple of
                // minutes into playback even with the video still playing.
                setWakeMode(C.WAKE_MODE_NETWORK)
                // Mime type left unset: media3 sniffs the container (mp4/mkv/webm...)
                val item = MediaItem.fromUri(Uri.parse("tgvfile://$fileId/$size"))
                // Resume where the user last left off (0 = play from the start)
                setMediaItem(item, startPosMs)
                // Subtitles setting drives both embedded selection and web fetch
                subsOn = Credentials.subsEnabled(this@PlayerActivity)
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setSelectUndeterminedTextLanguage(Credentials.subsEnabled(this@PlayerActivity))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
                    .build()
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errorView.visibility = View.VISIBLE
                        errorView.text = "Playback error: ${error.errorCodeName}. " +
                            "Check the network and reopen it."
                    }

                    // Finished: drop the resume bookmark so reopening the
                    // title starts fresh instead of "Continue" at the end
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            progressSaved = true
                            syncProgress(requestedClear = true)
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        maybeFetchExternalSubtitles(tracks)
                    }

                    override fun onCues(cues: List<Cue>) {
                        cuesView?.setCues(cues)
                    }
                })
            }
        player = exo
        playerView.player = exo
        // Keep the display on while the player runs (boxes dim/sleep otherwise)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * When the file ships no subtitle tracks at all and web subtitles are
     * enabled, resolves the title through [TmdbClient] and side-loads an
     * OpenSubtitles file (key compiled into release builds). The settings
     * panel offers the same lookup on demand for any file.
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

    /** True while a manual "Add subtitles" lookup is in flight. */
    private var manualSubsFetching = false

    /**
     * Settings-panel "Add subtitles": same [SubtitlesClient] pipeline as the
     * automatic fallback, but on demand - so it also works when the file
     * already has embedded tracks the user wants to replace or supplement.
     * Playback position survives the side-load ([attachSubtitle] re-opens
     * the media item at the same position).
     */
    private fun fetchSubtitlesManually() {
        val exo = player ?: return
        if (fileName.isBlank()) {
            Toast.makeText(this, "No file name to search for", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SubtitlesClient.hasKey(this)) {
            Toast.makeText(this, "OpenSubtitles key unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        if (manualSubsFetching) return
        manualSubsFetching = true
        Toast.makeText(this, "Searching subtitles for \"${fileName}\"…", Toast.LENGTH_SHORT).show()
        val context = applicationContext
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
            ))
            withContext(Dispatchers.Main) {
                manualSubsFetching = false
                if (fetched == null) {
                    TdLib.log("subs: manual lookup found nothing for '$fileName'")
                    Toast.makeText(
                        this@PlayerActivity,
                        "No subtitles found",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    attachSubtitle(exo, fetched.file, fetched.language, fetched.label)
                    Toast.makeText(
                        this@PlayerActivity,
                        "Subtitles added: ${Languages.nameOf(fetched.language)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-arm saving: a play -> background -> resume -> exit cycle should
        // persist the final position, not just the first stop
        progressSaved = false
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        // Persist where we stopped so the details screen can offer "Continue".
        // Skipped when the player just ended (already cleared).
        if (player != null && !progressSaved) {
            progressSaved = true
            syncProgress()
        }
    }

    /**
     * Writes or clears the resume bookmark. Near-the-end (>97%) and ended
     * positions count as watched: the row is dropped rather than resumed.
     * A fresh start (no resume seek) abandoned under 10s is also dropped -
     * the user clearly wanted to begin anew.
     *
     * Runs SYNCHRONOUSLY on the caller thread: onStop coincides with the
     * details screen's onResume re-query, and a posted coroutine would lose
     * that race (details would read the DB before the position lands).
     * The single-row SQLite write is tiny enough to do inline in onStop.
     */
    private fun syncProgress(requestedClear: Boolean = false) {
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val forceClear = requestedClear || (dur > 0L && pos >= dur * 97 / 100)
        val ctx = applicationContext
        when {
            forceClear -> VideoLibrary.clearProgress(ctx, chatId, msgId)
            pos < 10_000L -> if (startPosMs == 0L) {
                VideoLibrary.clearProgress(ctx, chatId, msgId)
            }
            else -> VideoLibrary.saveProgress(ctx, chatId, msgId, pos, dur)
        }
        TdLib.log("progress: chat=$chatId msg=$msgId pos=$pos dur=$dur clear=$forceClear")
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
