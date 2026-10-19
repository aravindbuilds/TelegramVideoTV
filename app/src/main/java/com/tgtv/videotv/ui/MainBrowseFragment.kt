package com.tgtv.videotv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import com.tgtv.videotv.R
import com.tgtv.videotv.model.VideoItem
import com.tgtv.videotv.model.VideoMapper
import com.tgtv.videotv.svc.DownloadService
import com.tgtv.videotv.td.Credentials
import com.tgtv.videotv.td.TdError
import com.tgtv.videotv.td.TdLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class MainBrowseFragment : BrowseSupportFragment() {

    private var loading = false
    private var sentToLogin = false

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { shadowEnabled = false },
    )
    private val actionsAdapter = ArrayObjectAdapter(ActionPresenter()).apply {
        addAll(
            0,
            listOf(
                ActionItem(ACTION_REFRESH, "↻", "Refresh", "Reload chats and videos"),
                ActionItem(ACTION_PAIR_VIA_PHONE, "◱", "Pair via phone", "Scan a QR with Telegram"),
                ActionItem(ACTION_SIGN_OUT, "⎋", "Sign out", "Forget this session and start over"),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cleaner full-width rows without the left rail
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        title = "TG Video TV"
        brandColor = resources.getColor(R.color.accent, null)
        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is VideoItem -> startActivity(PlayerActivity.intent(requireContext(), item))
                is ActionItem -> onAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = rowsAdapter
        TdLib.addListener(videoUpdateListener)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { loadContent() }
    }

    override fun onDestroyView() {
        TdLib.removeListener(videoUpdateListener)
        super.onDestroyView()
    }

    /** A new video message arrived live -> rebuild the rows a moment later. */
    private val videoUpdateListener: (org.drinkless.tdlib.TdApi.Object) -> Unit = { update ->
        if (update is org.drinkless.tdlib.TdApi.UpdateNewMessage &&
            VideoMapper.isVideoMessage(update.message.content)
        ) {
            rebuildJob?.cancel()
            rebuildJob = lifecycleScope.launch(Dispatchers.IO) {
                delay(1500)
                val activity = activity ?: return@launch
                if (!loading) {
                    loading = true
                    try {
                        buildRows(activity)
                    } finally {
                        loading = false
                    }
                }
            }
        }
    }

    private var rebuildJob: kotlinx.coroutines.Job? = null

    private fun onAction(a: ActionItem) {
        val activity = activity ?: return
        when (a.id) {
            ACTION_REFRESH -> {
                rowsAdapter.clear()
                loading = false
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { loadContent() }
            }
            ACTION_PAIR_VIA_PHONE -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // A stored bot session blocks user/QR login - reset TDLib first
                    TdLib.resetDb(activity.applicationContext)
                    activity.runOnUiThread {
                        startActivity(Intent(activity, QrLoginActivity::class.java))
                    }
                }
            }
            ACTION_SIGN_OUT -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    TdLib.resetDb(activity.applicationContext)
                    Credentials.clear(activity)
                    activity.runOnUiThread {
                        rowsAdapter.clear()
                        sentToLogin = false
                        loading = false
                        startActivity(Intent(activity, LoginActivity::class.java))
                    }
                }
            }
        }
    }

    private fun showVideoMenu(item: VideoItem) {
        val activity = activity ?: return
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setItems(
                    arrayOf("▶  Play (stream)", "⬇  Download to device", "Cancel"),
                ) { _, which ->
                    when (which) {
                        0 -> startActivity(PlayerActivity.intent(requireContext(), item))
                        1 -> {
                            DownloadService.start(activity, item)
                            Toast.makeText(
                                activity,
                                "Downloading \"${item.title}\"...",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            TdLib.authErrors.collect { message -> activity?.let { status(it, message) } }
        }
        if (!loading && rowsAdapter.size() <= 1) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { loadContent() }
        }
    }

    private suspend fun waitForReady(): Boolean =
        withTimeoutOrNull(30_000L) {
            while (TdLib.authState.value !is TdApi.AuthorizationStateReady) delay(250)
            true
        } ?: false

    private suspend fun loadContent() {
        val activity = activity ?: return
        if (!TdLib.isStarted) {
            if (!Credentials.hasApi(activity)) {
                if (!sentToLogin) {
                    sentToLogin = true
                    activity.runOnUiThread {
                        startActivity(Intent(activity, LoginActivity::class.java))
                    }
                }
                return
            }
            TdLib.start(activity.applicationContext)
        }
        if (TdLib.authState.value !is TdApi.AuthorizationStateReady) {
            // Telegram sign-in needs a few seconds over the network; wait up to 30 s
            status(activity, "Connecting to Telegram...")
            if (!waitForReady()) {
                if (!sentToLogin) {
                    sentToLogin = true
                    activity.runOnUiThread {
                        status(activity, null)
                        startActivity(Intent(activity, LoginActivity::class.java))
                    }
                } else {
                    status(activity, "Not connected yet - check your Telegram credentials")
                }
                return
            }
        }
        if (loading) return
        loading = true
        status(activity, "Loading your library...")
        try {
            buildRows(activity)
        } catch (e: TdError) {
            status(activity, "Telegram error: ${e.message}")
        } catch (e: Exception) {
            status(activity, "Failed to load: ${e.message}")
        } finally {
            loading = false
        }
    }

    private suspend fun buildRows(activity: Activity) {
        val rows = collectChats()
            .mapNotNull { chat ->
                val videos = videosFor(chat)
                if (videos.isEmpty()) null else VideoRow.from(chat, videos, hasMore = false)
            }
        activity.runOnUiThread {
            rowsAdapter.clear()
            if (rows.isEmpty()) {
                val isUser = Credentials.loginMode(activity) == Credentials.MODE_USER
                status(
                    activity,
                    if (isUser) {
                        "No videos found yet.\nPost or forward videos in a chat, then press Refresh."
                    } else {
                        "Bots cannot read chat history.\n" +
                            "Post a video in the group now - it will appear here live - " +
                            "or use \"Pair via phone\" and sign in with QR for the full library."
                    },
                )
            } else {
                status(activity, null)
            }
            for (row in rows) {
                val cardPresenter = VideoCardPresenter().apply {
                    onLongPress = { item -> showVideoMenu(item) }
                }
                val cards = ArrayObjectAdapter(cardPresenter)
                row.items.forEach { cards.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(row.title), cards))
            }
            rowsAdapter.add(ListRow(HeaderItem("Settings"), actionsAdapter))
        }
    }

    /**
     * Bots cannot call LoadChats/GetChats (TDLib 1.8+: "not available to bots"),
     * so wait for the UpdateNewChat stream TDLib delivers after Ready and use
     * the cached chats.
     */
    private suspend fun collectChats(): List<TdApi.Chat> {
        val deadline = withTimeoutOrNull(10_000L) {
            while (TdLib.chatCache.isEmpty()) delay(250)
            true
        }
        if (deadline == null) {
            // No UpdateNewChat arrived within 10 s: bots only learn chats as
            // they are added - surface that instead of failing silently
            TdLib.log("no chats received from update stream")
        }
        return TdLib.chatCache.values
            .filter { it.title.isNotBlank() }
            .sortedByDescending { it.lastMessage?.date ?: 0 }
    }

    /**
     * Videos per chat.
     * - User session (phone QR): full history + media search filters.
     * - Bot session: TDLib 1.8 forbids history/search for bots, so use the
     *   live-captured messages instead.
     */
    private suspend fun videosFor(chat: TdApi.Chat): List<VideoItem> {
        val all = mutableListOf<VideoItem>()
        val context = context
        val isUser = context != null &&
            Credentials.loginMode(context) == Credentials.MODE_USER

        if (isUser) {
            runCatching {
                TdLib.send(TdApi.GetChatHistory(chat.id, 0, 0, LIMIT, false))
            }.onSuccess { hist ->
                hist.messages?.forEach { VideoMapper.mapMessage(it, chat.title)?.let { v -> all.add(v) } }
            }

            if (all.size < LIMIT) {
                for (filter in VideoMapper.filters) {
                    runCatching {
                        TdLib.send(
                            TdApi.SearchChatMessages(chat.id, null, "", null, 0L, 0, LIMIT, filter),
                        )
                    }.onSuccess { found ->
                        found.messages?.forEach { VideoMapper.mapMessage(it, chat.title)?.let { v -> all.add(v) } }
                    }
                }
            }
        }

        // Live-captured videos work for both modes; bots rely on them exclusively
        TdLib.liveMessages[chat.id]?.forEach { VideoMapper.mapMessage(it, chat.title)?.let { v -> all.add(v) } }
        return all.distinctBy { it.messageId }.take(LIMIT)
    }

    private fun status(activity: Activity, message: String?) {
        activity.runOnUiThread { (activity as? MainActivity)?.setStatus(message) }
    }

    private companion object {
        const val LIMIT = 60
        const val ACTION_REFRESH = 1
        const val ACTION_PAIR_VIA_PHONE = 2
        const val ACTION_SIGN_OUT = 3
    }
}
