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
import com.tgtv.videotv.td.CloudBackup
import com.tgtv.videotv.td.ProgressSync
import com.tgtv.videotv.td.TdError
import com.tgtv.videotv.td.TdLib
import com.tgtv.videotv.store.VideoLibrary
import com.tgtv.videotv.model.Watchlist
import com.tgtv.videotv.ota.UpdateManager
import com.tgtv.videotv.ota.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

class MainBrowseFragment : BrowseSupportFragment() {

    private var loading = false
    private var sentToLogin = false

    // Kept as fields so their card subtitle can be swapped live as the
    // update state advances (check -> download -> install)
    private var updateAction =
        ActionItem(ACTION_CHECK_UPDATES, "⇣", "Check for updates", "Install the newest TGTV in-app")
    private var betaAction =
        ActionItem(ACTION_BETA_UPDATES, "β", "Beta updates", "Off — stable versions only")

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM).apply { shadowEnabled = false },
    )
    private val actionsAdapter = ArrayObjectAdapter(ActionPresenter()).apply {
        addAll(
            0,
            listOf(
                ActionItem(ACTION_REFRESH, "↻", "Refresh", "Reload chats and videos"),
                ActionItem(ACTION_ADD_CHATS, "✚", "Add chats", "Pin chats or groups to the library"),
                ActionItem(ACTION_PAIR_VIA_PHONE, "◱", "Pair via phone", "Scan a QR with Telegram"),
                ActionItem(ACTION_SIGN_OUT, "⎋", "Sign out", "Forget this session and start over"),
                ActionItem(ACTION_TMDB, "★", "TMDB metadata", "Real titles, years and ratings on the cards"),
                ActionItem(ACTION_SUBS, "ⓒ", "Subtitles", "On/off, languages and size"),
                ActionItem(ACTION_SEARCH, "⌕", "Search videos", "All chats, channels and groups"),
                ActionItem(ACTION_BACKUP, "☁", "Backup", "Save prefs + progress to Saved Messages"),
                updateAction,
                betaAction,
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cleaner full-width rows without the left rail
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        title = "TGTV"
        brandColor = resources.getColor(R.color.accent, null)
        setOnItemViewClickedListener { _, item, _, row ->
            when (item) {
                is VideoItem -> {
                    // Row siblings become the "Versions" list on the details screen
                    val siblings = (row as? ListRow)?.adapter?.let { a ->
                        (0 until a.size()).mapNotNull { a.get(it) as? VideoItem }
                    } ?: listOf(item)
                    startActivity(
                        DetailsActivity.intent(requireContext(), item, siblings),
                    )
                }
                is ActionItem -> onAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = rowsAdapter
        TdLib.addListener(videoUpdateListener)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { loadContent() }
        // Sync the beta opt-in card with the stored pref (default "Off" is
        // only correct when the pref was never set)
        activity?.let { updateBetaCard(it) }
        observeUpdates()
        // Silent update check (like the phone app): once per process, a few
        // seconds after launch so the library paints first; a new version
        // gets the same offer dialog a manual check shows.
        if (!autoUpdateChecked) {
            autoUpdateChecked = true
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                delay(4_000)
                context?.let { UpdateManager.check(it) }
            }
        }
        // The leanback title is an inflating TextView; find it and brand it
        // (bold red TGTV wordmark)
        view.post {
            val found = ArrayList<View>()
            view.findViewsWithText(found, "TGTV", View.FIND_VIEWS_WITH_TEXT)
            val tv = found.firstOrNull() as? android.widget.TextView ?: return@post
            tv.typeface = android.graphics.Typeface.create(
                "sans-serif-black",
                android.graphics.Typeface.BOLD,
            )
            tv.textSize = 34f
            tv.setTextColor(0xFFE50914.toInt())
            tv.letterSpacing = -0.02f
        }
    }

    override fun onDestroyView() {
        TdLib.removeListener(videoUpdateListener)
        super.onDestroyView()
    }

    /** A new video message arrived live -> store it and rebuild the rows soon. */
    private val videoUpdateListener: (org.drinkless.tdlib.TdApi.Object) -> Unit = { update ->
        if (update is org.drinkless.tdlib.TdApi.UpdateNewMessage) {
            val message = update.message
            val context = context
            // Remote progress bookmark from the other device -> merge + repaint
            val isBookmark = context != null &&
                ProgressSync.observeMessage(context, message.chatId, message)
            when {
                isBookmark -> {
                    rebuildJob?.cancel()
                    rebuildJob = lifecycleScope.launch(Dispatchers.IO) {
                        delay(1_500)
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

                VideoMapper.isVideoMessage(message.content) -> {
                    // Persist the reference so restarts skip the rescan entirely
                    val chatTitle = TdLib.chatCache[message.chatId]?.title
                    val mapped = context?.let { c ->
                        VideoMapper.mapMessage(message, chatTitle ?: "Saved Messages")
                    }
                    if (context != null && mapped != null) {
                        VideoLibrary.upsert(context, mapped, message.date)
                    }
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
            ACTION_TMDB -> showTmdbDialog(activity)
            ACTION_SUBS -> showSubtitlesDialog(activity)
            ACTION_ADD_CHATS -> {
                showLibraryChatsDialog(activity)
                // Pins changed -> fold them into the cloud backup (throttled)
                CloudBackup.request(activity)
            }
            ACTION_SEARCH -> showSearchInput(activity)
            ACTION_BACKUP -> {
                Toast.makeText(activity, "Backing up to Saved Messages...", Toast.LENGTH_SHORT).show()
                CloudBackup.request(activity, force = true)
            }
            ACTION_CHECK_UPDATES -> onUpdateCardTap(activity)
            ACTION_BETA_UPDATES -> showBetaUpdatesDialog(activity)
        }
    }

    private fun showSubtitlesDialog(activity: Activity) {
        val sizeIdx = Credentials.subsTextSize(activity)
        val sizeLabels = listOf("Small", "Medium", "Large")
        val items = listOf(
            "Subtitles: ${if (Credentials.subsEnabled(activity)) "On" else "Off"}",
            "Subtitle size: ${sizeLabels.getOrElse(sizeIdx) { sizeLabels[0] }}",
            "Languages",
            "Close",
        )
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Subtitles")
                .setItems(items.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> Credentials.setSubsEnabled(
                            activity,
                            !Credentials.subsEnabled(activity),
                        )

                        1 -> Credentials.setSubsTextSize(
                            activity,
                            (Credentials.subsTextSize(activity) + 1) % sizeLabels.size,
                        )

                        2 -> subsLanguagesInput(activity)
                        else -> return@setItems
                    }
                    if (which != 3) showSubtitlesDialog(activity)
                }
                .show()
        }
    }

    private fun subsLanguagesInput(activity: Activity) {
        val input = android.widget.EditText(activity).apply {
            hint = "en, hi, ml"
            setSingleLine()
            setText(Credentials.subsLanguages(activity))
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Subtitle languages")
                .setMessage("Comma-separated ISO language codes, in priority order.")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    Credentials.setSubsLanguages(activity, input.text.toString())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showTmdbDialog(activity: Activity) {
        val on = com.tgtv.videotv.td.TmdbClient.enabled(activity.applicationContext)
        val override = Credentials.tmdbKey(activity)
        val stateLine = when {
            on && override == null -> "On, using the built-in key." +
                " Cards show the TMDB title, year and rating."
            on -> "On, using your custom key (${override!!.take(4)}…${override.takeLast(4)})."
            else -> "Off."
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("TMDB metadata")
                .setMessage(stateLine)
                .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                    Credentials.setTmdbDisabled(activity, on)
                    refreshRows()
                }
                .setNegativeButton("Custom key") { _, _ -> tmdbKeyInput(activity) }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun tmdbKeyInput(activity: Activity) {
        val input = android.widget.EditText(activity).apply {
            hint = "32-character API key"
            setSingleLine()
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Custom TMDB key")
                .setMessage(
                    "Optional: use your own key from themoviedb.org (Settings -> API) " +
                        "instead of the built-in one.",
                )
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    Credentials.setTmdbKey(activity, input.text.toString())
                    Credentials.setTmdbDisabled(activity, false)
                    refreshRows()
                }
                .setNeutralButton("Back", null)
                .show()
        }
    }

    private fun refreshRows() {
        rowsAdapter.clear()
        loading = false
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { loadContent() }
    }

    private fun showVideoMenu(item: VideoItem) {
        val activity = activity ?: return
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setItems(
                    arrayOf(
                        "▶  Play (stream)",
                        "⬇  Download to device",
                        "✕  Remove from TGTV",
                        "Cancel",
                    ),
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
                        2 -> {
                            forceRebuild = true
                            VideoLibrary.hide(activity, item.chatId, item.messageId)
                            Toast.makeText(
                                activity,
                                "Removed from TGTV (stays in Telegram)",
                                Toast.LENGTH_SHORT,
                            ).show()
                            rebuildJob?.cancel()
                            rebuildJob = lifecycleScope.launch(Dispatchers.IO) {
                                delay(300)
                                buildRows(activity)
                            }
                        }
                    }
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user just granted "Install unknown apps" for us, the pending
        // installer launches right away (phone-app behavior)
        UpdateManager.resumeInstall(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            TdLib.authErrors.collect { message -> activity?.let { status(it, message) } }
        }
        // Rows must be rebuilt after a Remove / Watchlist change on the
        // details screen; the DB makes the rebuild instant.
        if (forceRebuild) {
            forceRebuild = false
            rebuildJob?.cancel()
            rebuildJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (loading) return@launch
                loading = true
                try {
                    activity?.let { buildRows(it) }
                } finally {
                    loading = false
                }
            }
        } else if (!loading && rowsAdapter.size() <= 1) {
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
            // One cloud restore per process: merges the newest Saved
            // Messages backup (prefs + watchlist + progress from the phone)
            if (!cloudRestored) {
                cloudRestored = true
                CloudBackup.restore(activity)
            }
        } catch (e: TdError) {
            status(activity, "Telegram error: ${e.message}")
        } catch (e: Exception) {
            status(activity, "Failed to load: ${e.message}")
        } finally {
            loading = false
        }
    }

    private suspend fun buildRows(activity: Activity) {
        // Fast paint (mobile app's strategy): the local SQLite index puts
        // the library on screen instantly - no Telegram round trips - while
        // the full scan below runs; it repaints with anything newer.
        if (rowsAdapter.size() <= 1) {
            val quick = quickRows(activity)
            if (quick.isNotEmpty()) paintRows(activity, quick, emptyState = false)
        }
        val rows = mutableListOf<VideoRow>()
        // Active search results lead the screen until cleared
        searchResults?.takeIf { it.isNotEmpty() && searchQuery != null }?.let {
            rows.add(VideoRow(-2L, "Search: \"$searchQuery\"", it, hasMore = false))
        }
        // The whole watchlist leads the screen like a Netflix "My List" row
        val saved = Watchlist.all(activity)
        if (saved.isNotEmpty()) {
            rows.add(VideoRow(-1L, "My List", saved, hasMore = false))
        }
        rows.addAll(
            collectChats()
                .mapNotNull { chat ->
                    val videos = videosFor(chat)
                    if (videos.isEmpty()) null else VideoRow.from(chat, videos, hasMore = false)
                },
        )
        paintRows(activity, rows, emptyState = true)
    }

    /** Pushes [rows] into the adapter on the UI thread. */
    private fun paintRows(activity: Activity, rows: List<VideoRow>, emptyState: Boolean) {
        activity.runOnUiThread {
            rowsAdapter.clear()
            if (rows.isEmpty()) {
                if (emptyState) {
                    val isUser = TdLib.sessionIsUser
                    status(
                        activity,
                        if (isUser) {
                            "No videos in Saved Messages yet.\n" +
                                "On your phone, send or forward a video to \"Saved Messages\",\n" +
                                "then press Refresh - or pin more chats: Settings -> Add chats."
                        } else {
                            "Bots cannot read chat history, but what you have seen\n" +
                                "is already saved here - forward or post a new video too.\n" +
                                "For the full history use \"Pair via phone\" and sign in with QR."
                        },
                    )
                }
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
     * Rows built purely from the local SQLite index - zero Telegram calls.
     * Painted first so a cold start with a warm index never shows
     * "Loading your library..."; the live scan refreshes right after.
     */
    private fun quickRows(activity: Activity): List<VideoRow> {
        val context = context ?: return emptyList()
        val rows = mutableListOf<VideoRow>()
        val saved = Watchlist.all(activity)
        if (saved.isNotEmpty()) {
            rows.add(VideoRow(-1L, "My List", saved, hasMore = false))
        }
        VideoLibrary.chatIds(context).forEach { (id, title, _) ->
            val items = VideoLibrary.chatItems(context, id)
            if (items.isNotEmpty()) {
                rows.add(VideoRow(id, title.ifBlank { "Chat $id" }, items, hasMore = false))
            }
        }
        return rows
    }

    /**
     * The library's chat list.
     * - User sessions (phone QR): pinned to Saved Messages only — the whole
     *   account stays private on a shared TV.
     * - Bots cannot call LoadChats/GetChats (TDLib 1.8+: "not available to
     *   bots"), so wait for the UpdateNewChat stream TDLib delivers after
     *   Ready and use the cached chats.
     */
    private suspend fun collectChats(): List<TdApi.Chat> {
        // Trust the live session type (GetMe) - the login-mode pref can be
        // stale after pairing via phone QR without passing LoginActivity
        val isUser = TdLib.ensureMe()
        if (isUser) {
            // Phone/QR user sessions can see the entire account, which is
            // overwhelming (and privacy-hostile) on a shared TV. Pin the
            // library to Saved Messages by default - the one chat that
            // exists precisely for this "send it to my TV" use. The Saved
            // Messages chat id IS the user's own id, so GetChat resolves it
            // directly without paging the whole chat list. Anything the
            // user added via Settings -> Add chats joins it.
            val userId = TdLib.me?.id ?: return emptyList()
            val chats = mutableListOf<TdApi.Chat>()
            runCatching { TdLib.send(TdApi.GetChat(userId)) as? TdApi.Chat }
                .getOrNull()?.let { chats.add(it) }
            val savedId = chats.firstOrNull()?.id
            pinnedChatIds().filter { it != savedId }.forEach { id ->
                runCatching { TdLib.send(TdApi.GetChat(id)) as? TdApi.Chat }
                    .getOrNull()?.let { chats.add(it) }
            }
            return chats
        }
        // Chats stored in the local index let the library paint right away;
        // only when there is NOTHING stored yet (fresh bot install) wait for
        // the UpdateNewChat stream - bots learn chats only as messages land.
        val ctx = context
        val storedChats = ctx?.let { VideoLibrary.chatIds(it) } ?: emptyList()
        if (TdLib.chatCache.isEmpty() && storedChats.isEmpty()) {
            val deadline = withTimeoutOrNull(10_000L) {
                while (TdLib.chatCache.isEmpty()) delay(250)
                true
            }
            if (deadline == null) {
                // No UpdateNewChat arrived within 10 s: bots only learn chats
                // as they are added - surface that instead of failing silently
                TdLib.log("no chats received from update stream")
            }
        }
        val chats = TdLib.chatCache.values
            .filter { it.title.isNotBlank() }
            .toMutableList()
        val known = chats.map { it.id }.toMutableSet()

        // Chats stored in the local index reappear instantly on restart.
        // This is what makes bot sessions survive cold starts: TDLib 1.8
        // announces a group to the bot only when a NEW message lands there,
        // so without this the library would stay empty until one does.
        val storedDates = mutableMapOf<Long, Int>()
        storedChats.forEach { (id, title, newest) ->
            if (!known.add(id)) return@forEach
            storedDates[id] = newest
            val live = runCatching {
                TdLib.send(TdApi.GetChat(id)) as? TdApi.Chat
            }.getOrNull()
            chats.add(
                live ?: TdApi.Chat().apply {
                    this.id = id
                    this.title = title
                },
            )
        }
        return chats.sortedByDescending { it.lastMessage?.date ?: storedDates[it.id] ?: 0 }
    }

    /** Chat ids pinned from Settings -> Add chats, as Longs. */
    private fun pinnedChatIds(): List<Long> =
        context?.let { Credentials.libraryChats(it).mapNotNull { s -> s.toLongOrNull() } }
            ?: emptyList()

    /**
     * Settings -> Add chats: a multi-select of every chat this session knows
     * (chat list for user sessions, chat cache + local index for bots).
     * Pinned chats join Saved Messages in the library; unpinning hides their
     * rows again. Titles come from the live cache, falling back to the local
     * index so never-opened chats still read sensibly.
     */
    private fun showLibraryChatsDialog(activity: Activity) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val isUser = TdLib.ensureMe()
            if (isUser) TdLib.loadChatsBlocking()
            val storedTitles = context
                ?.let { VideoLibrary.chatIds(it).associate { (id, title, _) -> id to title } }
                ?: emptyMap()
            val candidates = (TdLib.chatCache.values.map { it.id } + storedTitles.keys)
                .distinct()
                .filter { it != TdLib.me?.id }
                .map { id ->
                    TdLib.chatCache[id]
                        ?: TdApi.Chat().apply {
                            this.id = id
                            title = storedTitles[id] ?: "Chat $id"
                        }
                }
                .sortedByDescending { it.lastMessage?.date ?: 0 }
            if (candidates.isEmpty()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "No chats seen yet - press Refresh first", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val pinned = Credentials.libraryChats(activity).toMutableSet()
            activity.runOnUiThread {
                val titles = candidates.map { it.title.ifBlank { "Chat ${it.id}" } }
                val checked = candidates.map { it.id.toString() in pinned }.toBooleanArray()
                AlertDialog.Builder(activity)
                    .setTitle("Library chats")
                    .setMultiChoiceItems(
                        titles.toTypedArray(),
                        checked,
                    ) { _, which, isChecked ->
                        val id = candidates[which].id.toString()
                        if (isChecked) pinned.add(id) else pinned.remove(id)
                    }
                    .setPositiveButton("Save") { _, _ ->
                        Credentials.setLibraryChats(activity, pinned)
                        refreshRows()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /**
     * Videos per chat.
     * - User session (phone QR): full history + media search filters.
     * - Bot session: TDLib 1.8 forbids history/search for bots, so use the
     *   live-captured messages instead.
     */
    private suspend fun videosFor(chat: TdApi.Chat): List<VideoItem> {
        val context = context ?: return emptyList()
        val scanned = mutableListOf<VideoItem>()
        val isUser = TdLib.sessionIsUser ||
            Credentials.loginMode(context) == Credentials.MODE_USER

        if (isUser) {
            val found = mutableListOf<Pair<TdApi.Message, VideoItem>>()
            runCatching {
                TdLib.send(TdApi.GetChatHistory(chat.id, 0, 0, LIMIT, false))
            }.onSuccess { hist ->
                hist.messages?.forEach {
                    // Sync bookmarks from the other device merge into the
                    // local store here; they are never shown as videos
                    if (ProgressSync.observeMessage(context, chat.id, it)) return@forEach
                    VideoMapper.mapMessage(it, chat.title)?.let { v -> found.add(it to v) }
                }
            }

            if (found.size < LIMIT) {
                for (filter in VideoMapper.filters) {
                    runCatching {
                        TdLib.send(
                            TdApi.SearchChatMessages(chat.id, null, "", null, 0L, 0, LIMIT, filter),
                        )
                    }.onSuccess { res ->
                        res.messages?.forEach {
                            VideoMapper.mapMessage(it, chat.title)?.let { v -> found.add(it to v) }
                        }
                    }
                }
            }
            found.forEach { (msg, item) ->
                scanned.add(item)
                VideoLibrary.upsert(context, item, msg.date)
            }
        }

        // Live-captured messages from this session (both modes)
        TdLib.liveMessages[chat.id]?.forEach {
            VideoMapper.mapMessage(it, chat.title)?.let { v ->
                scanned.add(v)
                VideoLibrary.upsert(context, v, it.date)
            }
        }

        // Then the stored index (main source for bots; fast-start for users)
        val seen = scanned.map { it.chatId to it.messageId }.toMutableSet()
        VideoLibrary.chatItems(context, chat.id).forEach { stored ->
            val key = stored.chatId to stored.messageId
            if (!seen.add(key)) return@forEach
            scanned.add(stored)
        }
        // Newest first, hidden entries filtered out
        return scanned
            .distinctBy { it.chatId to it.messageId }
            .filter { !VideoLibrary.isHidden(context, it.chatId, it.messageId) }
            .sortedByDescending { it.messageId }
            .take(LIMIT + 40)
    }

    private fun status(activity: Activity, message: String?) {
        activity.runOnUiThread { (activity as? MainActivity)?.setStatus(message) }
    }

    // ---------------------------------------------------------------- search

    /** Current search results (in-memory only - the index stays untouched). */
    private var searchResults: List<VideoItem>? = null
    private var searchQuery: String? = null

    private fun showSearchInput(activity: Activity) {
        val input = android.widget.EditText(activity).apply {
            hint = "Movie, series, channel file..."
            setSingleLine()
            setText(searchQuery)
        }
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Search all of Telegram")
                .setMessage(
                    "Searches every chat, channel and group on this account " +
                        "plus the local library - not just your pinned chats.",
                )
                .setView(input)
                .setPositiveButton("Search") { _, _ ->
                    runSearch(activity, input.text.toString())
                }
                .setNeutralButton("Clear") { _, _ ->
                    searchResults = null
                    searchQuery = null
                    refreshRows()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Local index first (instant), then Telegram's global account search. */
    private fun runSearch(activity: Activity, raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = context ?: return@launch
            val results = LinkedHashMap<Pair<Long, Long>, VideoItem>()
            VideoLibrary.search(context, q).forEach { results[it.chatId to it.messageId] = it }
            if (TdLib.sessionIsUser) {
                // Global search covers every chat the account can see,
                // including broadcast channels you never pinned to the TV
                val filters = listOf(
                    TdApi.SearchMessagesFilterVideo(),
                    TdApi.SearchMessagesFilterDocument(),
                )
                for (filter in filters) {
                    runCatching {
                        TdLib.send(TdApi.SearchMessages(null, q, "", 50, filter, null, 0, 0))
                    }.onSuccess { found ->
                        found.messages?.forEach { msg ->
                            val title = TdLib.chatCache[msg.chatId]?.title
                                ?: runCatching {
                                    (TdLib.send(TdApi.GetChat(msg.chatId)) as? TdApi.Chat)?.title
                                }.getOrNull() ?: "Chat"
                            VideoMapper.mapMessage(msg, title)?.let { v ->
                                results.putIfAbsent(v.chatId to v.messageId, v)
                            }
                        }
                    }
                }
            }
            searchQuery = q
            searchResults = results.values.toList()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                activity?.let { buildRows(it) }
            }
            if (searchResults?.isEmpty() == true) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "No videos found for \"$q\"", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- updates

    /** True while the running check was started silently at launch (no toasts). */
    private var silentUpdates = true

    /** The check-for-updates offer/progress dialog, when one is on screen. */
    private var updateDialog: AlertDialog? = null

    /** Tag already offered in a dialog - avoids re-popping it on every emission. */
    private var offeredTag: String? = null

    /** Renders every [UpdateManager.state] change: dialogs, toasts, card subtitle. */
    private fun observeUpdates() {
        viewLifecycleOwner.lifecycleScope.launch {
            UpdateManager.state.collect { st ->
                val activity = activity ?: return@collect
                updateUpdateCard(st)
                when (st.phase) {
                    UpdateManager.Phase.AVAILABLE -> showUpdateOffer(activity, st)

                    UpdateManager.Phase.DOWNLOADING -> showDownloadProgress(activity, st)

                    UpdateManager.Phase.READY -> {
                        dismissUpdateDialog()
                        tryInstall(activity)
                    }

                    UpdateManager.Phase.UP_TO_DATE -> {
                        dismissUpdateDialog()
                        if (!silentUpdates) {
                            Toast.makeText(
                                activity,
                                "You're on the latest version (v${st.tag})",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    UpdateManager.Phase.FAILED -> {
                        dismissUpdateDialog()
                        if (!silentUpdates) {
                            Toast.makeText(
                                activity,
                                "Update check failed - check your connection and try again",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /** Tap on the "Check for updates" card: resume/advance the current flow. */
    private fun onUpdateCardTap(activity: Activity) {
        silentUpdates = false
        when (UpdateManager.state.value.phase) {
            UpdateManager.Phase.AVAILABLE -> showUpdateOffer(activity, UpdateManager.state.value)
            UpdateManager.Phase.DOWNLOADING ->
                showDownloadProgress(activity, UpdateManager.state.value)

            UpdateManager.Phase.READY -> tryInstall(activity)

            UpdateManager.Phase.CHECKING ->
                Toast.makeText(activity, "Checking for updates...", Toast.LENGTH_SHORT).show()

            else -> UpdateManager.check(activity)
        }
    }

    private fun showUpdateOffer(activity: Activity, st: UpdateManager.UpdateState) {
        if (offeredTag == st.tag && updateDialog?.isShowing == true) return
        offeredTag = st.tag
        updateDialog = AlertDialog.Builder(activity)
            .setTitle("Update available${if (st.prerelease) " (beta)" else ""}")
            .setMessage("TGTV v${st.tag} is ready to install.")
            .setPositiveButton("Download") { _, _ -> UpdateManager.download(activity) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showDownloadProgress(activity: Activity, st: UpdateManager.UpdateState) {
        val existing = updateDialog
        if (existing != null && existing.isShowing) {
            existing.setTitle("Downloading update")
            existing.setMessage("TGTV v${st.tag} — ${st.progress}%")
            return
        }
        updateDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading update")
            .setMessage("TGTV v${st.tag} — ${st.progress}%")
            .setNegativeButton("Hide", null)
            .show()
    }

    private fun tryInstall(activity: Activity) {
        when (UpdateManager.install(activity)) {
            Updater.InstallResult.NEEDS_PERMISSION ->
                Toast.makeText(
                    activity,
                    "Allow \"Install unknown apps\" for TGTV, then try again",
                    Toast.LENGTH_LONG,
                ).show()

            Updater.InstallResult.FAILED ->
                Toast.makeText(activity, "Could not start the installer - try again", Toast.LENGTH_SHORT)
                    .show()

            else -> Unit
        }
    }

    private fun dismissUpdateDialog() {
        updateDialog?.dismiss()
        updateDialog = null
    }

    /** Live subtitle on the "Check for updates" card as the flow advances. */
    private fun updateUpdateCard(st: UpdateManager.UpdateState) {
        val sub = when (st.phase) {
            UpdateManager.Phase.AVAILABLE ->
                "v${st.tag}${if (st.prerelease) " (beta)" else ""} available"
            UpdateManager.Phase.DOWNLOADING -> "Downloading v${st.tag} — ${st.progress}%"
            UpdateManager.Phase.READY -> "v${st.tag} ready — select to install"
            UpdateManager.Phase.UP_TO_DATE -> "Up to date (v${st.tag})"
            UpdateManager.Phase.FAILED -> "Check failed — select to retry"
            else -> "Install the newest TGTV in-app"
        }
        if (updateAction.sub != sub) {
            val pos = actionsAdapter.indexOf(updateAction)
            if (pos >= 0) {
                updateAction = updateAction.copy(sub = sub)
                actionsAdapter.replace(pos, updateAction)
            }
        }
    }

    /** Beta opt-in dialog, matching the TMDB dialog's on/off pattern. */
    private fun showBetaUpdatesDialog(activity: Activity) {
        val on = Credentials.betaUpdates(activity)
        AlertDialog.Builder(activity)
            .setTitle("Beta updates")
            .setMessage(
                if (on) {
                    "On - beta (pre-release) versions are offered in-app as soon as they publish."
                } else {
                    "Off - only stable versions are offered in-app."
                },
            )
            .setPositiveButton(if (on) "Stable only" else "Include betas") { _, _ ->
                Credentials.setBetaUpdates(activity, !on)
                updateBetaCard(activity)
                UpdateManager.check(activity, !on)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun updateBetaCard(activity: Activity) {
        val sub = if (Credentials.betaUpdates(activity)) "On — betas offered too" else "Off — stable versions only"
        if (betaAction.sub != sub) {
            val pos = actionsAdapter.indexOf(betaAction)
            if (pos >= 0) {
                betaAction = betaAction.copy(sub = sub)
                actionsAdapter.replace(pos, betaAction)
            }
        }
    }

    companion object {
        const val LIMIT = 60
        const val ACTION_REFRESH = 1
        const val ACTION_PAIR_VIA_PHONE = 2
        const val ACTION_SIGN_OUT = 3
        const val ACTION_TMDB = 4
        const val ACTION_SUBS = 5
        const val ACTION_ADD_CHATS = 6
        const val ACTION_CHECK_UPDATES = 7
        const val ACTION_BETA_UPDATES = 8
        const val ACTION_SEARCH = 9
        const val ACTION_BACKUP = 10

        /** Set when a Remove happens elsewhere; guarantees a row rebuild. */
        var forceRebuild = false

        /** The silent update check runs once per process, not per fragment. */
        var autoUpdateChecked = false

        /** The cloud-backup restore runs once per process. */
        var cloudRestored = false
    }
}