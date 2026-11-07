package com.tgtv.videotv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    // Card renderers, shared by every row so image caches and the
    // long-press handler are reused across rebuilds.
    /** chatId:messageId -> (posMs, durMs, updatedMs), refreshed per rebuild. */
    private var progressMap: Map<String, Triple<Long, Long, Long>> = emptyMap()
    private val videoCardPresenter = VideoCardPresenter().apply {
        onLongPress = { item -> showVideoMenu(item) }
    }
    private val continueCardPresenter = ContinueCardPresenter().apply {
        onLongPress = { item -> showVideoMenu(item) }
        progressProvider = { item ->
            progressMap["${item.chatId}:${item.messageId}"]?.let { it.first to it.second }
        }
    }
    private val detailRowPresenter = DetailRowPresenter().apply {
        onLongPress = { item -> showVideoMenu(item) }
    }

    // Kept as fields so their card subtitle can be swapped live as the
    // update state advances (check -> download -> install)
    private var updateAction =
        ActionItem(ACTION_CHECK_UPDATES, "⇣", "Check for updates", "Install the newest TGTV in-app")
    private var betaAction =
        ActionItem(ACTION_BETA_UPDATES, "β", "Beta updates", "Off — stable versions only")
    private var sortViewAction =
        ActionItem(ACTION_SORT_VIEW, "▤", "Sort & view", "Newest • Cards")
    private var syncAction =
        ActionItem(ACTION_BACKUP, "⇄", "Sync across devices", "On — continue on any device")
    private var iconAction =
        ActionItem(ACTION_APP_ICON, "◉", "App icon", "CRT")

    private val rowsAdapter = ArrayObjectAdapter(
        ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM).apply { shadowEnabled = false },
    )
    private val actionsAdapter = ArrayObjectAdapter(ActionPresenter()).apply {
        addAll(
            0,
            listOf(
                // Ordered by usability: frequent actions first, one-time
                // setup (QR pairing, sign out) and update toggles last
                ActionItem(ACTION_SEARCH, "⌕", "Search videos", "All chats, channels and groups"),
                ActionItem(ACTION_REFRESH, "↻", "Refresh", "Reload chats and videos"),
                sortViewAction,
                ActionItem(ACTION_ADD_CHATS, "✚", "Add chats", "Pin chats or groups to the library"),
                syncAction,
                ActionItem(ACTION_SUBS, "ⓒ", "Subtitles", "On/off, languages and size"),
                ActionItem(ACTION_TMDB, "★", "TMDB metadata", "Real titles, years and ratings on the cards"),
                iconAction,
                ActionItem(ACTION_PAIR_VIA_PHONE, "◱", "Pair via phone", "Scan a QR with Telegram"),
                ActionItem(ACTION_SIGN_OUT, "⎋", "Sign out", "Forget this session and start over"),
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
                    // "Continue Watching" plays on right where you left off,
                    // like the phone app; every other card opens details
                    if (row is ListRow && row.headerItem.name == "Continue Watching") {
                        val ctx = requireContext()
                        val pos = VideoLibrary.progressOf(ctx, item.chatId, item.messageId)?.first
                            ?: 0L
                        startActivity(PlayerActivity.intent(ctx, item, pos))
                    } else {
                        // Row siblings become the "Versions" list on the details screen
                        val siblings = (row as? ListRow)?.adapter?.let { a ->
                            (0 until a.size()).mapNotNull { a.get(it) as? VideoItem }
                        } ?: listOf(item)
                        startActivity(
                            DetailsActivity.intent(requireContext(), item, siblings),
                        )
                    }
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
        syncSortViewCard()
        syncSyncCard()
        syncAppIconCard()
        // App updates reset enabled components to the manifest defaults;
        // re-apply the stored icon choice so launcher and card agree
        activity?.let { runCatching { applyLauncherIcon(it, Credentials.appIcon(it)) } }
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
            ACTION_SORT_VIEW -> showSortViewDialog(activity)
            ACTION_APP_ICON -> showAppIconDialog(activity)
            ACTION_ADD_CHATS -> {
                showLibraryChatsDialog(activity)
                // Pins changed -> fold them into the cloud backup (throttled)
                CloudBackup.request(activity)
            }
            ACTION_SEARCH -> showSearchInput(activity)
            ACTION_BACKUP -> showSyncDialog(activity)
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

    /**
     * Settings -> Sort & view: every option is listed with the active one
     * marked, so the user always sees what a tap switches to. Tapping an
     * option applies it immediately and re-renders the marks; rows stay
     * navigable with the D-pad.
     */
    private fun showSortViewDialog(activity: Activity) {
        val sorts = listOf("Newest first", "Oldest first", "Title A-Z", "Largest first")
        val views = listOf("Cards (posters)", "Detailed (file list)")
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val optionViews = mutableListOf<Pair<android.widget.TextView, Int>>()

        fun renderMarks() {
            val sortSel = Credentials.homeSort(activity)
            val viewSel = Credentials.homeView(activity)
            optionViews.forEach { (tv, option) ->
                val active = if (option < sorts.size) option == sortSel else option - sorts.size == viewSel
                val label = if (option < sorts.size) sorts[option] else views[option - sorts.size]
                tv.text = if (active) "✓  $label" else "    $label"
                tv.setTextColor(if (active) 0xFFE50914.toInt() else 0xFFEDEDED.toInt())
            }
        }

        fun addSection(title: String) {
            container.addView(
                android.widget.TextView(activity).apply {
                    text = title
                    textSize = 13f
                    setPadding(0, pad / 2, 0, pad / 4)
                },
            )
        }

        fun addOption(option: Int) {
            val tv = android.widget.TextView(activity).apply {
                textSize = 16f
                setPadding(pad / 3, pad / 2, pad / 3, pad / 2)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    if (option < sorts.size) {
                        Credentials.setHomeSort(activity, option)
                    } else {
                        Credentials.setHomeView(activity, option - sorts.size)
                    }
                    renderMarks()
                    syncSortViewCard()
                    refreshRows()
                }
                setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.04f else 1f)
                        .scaleY(if (focused) 1.04f else 1f).setDuration(100).start()
                }
            }
            optionViews.add(tv to option)
            container.addView(tv)
        }

        addSection("SORT ORDER")
        sorts.indices.forEach { addOption(it) }
        addSection("VIEW")
        (sorts.size until sorts.size + views.size).forEach { addOption(it) }
        renderMarks()

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Sort & view")
                .setView(container)
                .setPositiveButton("Done", null)
                .show()
        }
    }

    /**
     * Launcher icon picker. Switching flips which activity-alias is enabled
     * (the manifest's launcher entry), so the icon changes without a
     * reinstall. The target alias is enabled before the others are disabled
     * so the launcher never ends up with zero entries.
     */
    private fun showAppIconDialog(activity: Activity) {
        val themes = listOf(
            Credentials.ICON_CRT to "CRT (classic blue)",
            Credentials.ICON_NOIR to "Noir (amber glow)",
            Credentials.ICON_CYBERPUNK to "Cyberpunk (neon)",
        )
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val optionViews = mutableListOf<Pair<android.widget.TextView, String>>()

        fun renderMarks() {
            val sel = Credentials.appIcon(activity)
            optionViews.forEach { (tv, key) ->
                tv.text = if (key == sel) "✓  ${themes.first { it.first == key }.second}" else "    ${themes.first { it.first == key }.second}"
                tv.setTextColor(if (key == sel) 0xFFE50914.toInt() else 0xFFEDEDED.toInt())
            }
        }

        themes.forEach { (key, label) ->
            val tv = android.widget.TextView(activity).apply {
                text = label
                textSize = 16f
                setPadding(pad / 3, pad / 2, pad / 3, pad / 2)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    Credentials.setAppIcon(activity, key)
                    applyLauncherIcon(activity, key)
                    renderMarks()
                    syncAppIconCard()
                }
                setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.04f else 1f)
                        .scaleY(if (focused) 1.04f else 1f).setDuration(100).start()
                }
            }
            optionViews.add(tv to key)
            container.addView(tv)
        }
        renderMarks()

        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("App icon")
                .setView(container)
                .setPositiveButton("Done", null)
                .show()
        }
    }

    /** Enables the [key] theme's alias, disables the other two. */
    private fun applyLauncherIcon(context: Context, key: String) {
        val aliases =
            mapOf(
                Credentials.ICON_CRT to ".IconCrt",
                Credentials.ICON_NOIR to ".IconNoir",
                Credentials.ICON_CYBERPUNK to ".IconCyberpunk",
            )
        val pm = context.packageManager
        val pkg = context.packageName
        // ComponentName needs the fully-qualified class name (the ".Name"
        // shorthand is manifest-only)
        fun set(alias: String, enabled: Boolean) {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, pkg + alias),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
        set(aliases.getValue(key), enabled = true)
        aliases.filterKeys { it != key }.forEach { set(it.value, enabled = false) }
    }

    /** Live subtitle on the "App icon" card. */
    private fun syncAppIconCard() {
        val activity = activity ?: return
        val label = when (Credentials.appIcon(activity)) {
            Credentials.ICON_NOIR -> "Noir"
            Credentials.ICON_CYBERPUNK -> "Cyberpunk"
            else -> "CRT"
        }
        if (iconAction.sub != label) {
            val pos = actionsAdapter.indexOf(iconAction)
            if (pos >= 0) {
                iconAction = iconAction.copy(sub = label)
                actionsAdapter.replace(pos, iconAction)
            }
        }
    }

    /** Live subtitle on the "Sort & view" card reflecting the current choice. */
    private fun syncSortViewCard() {
        val activity = activity ?: return
        val sorts = listOf("Newest", "Oldest", "Title A-Z", "Largest")
        val sub = sorts.getOrElse(Credentials.homeSort(activity)) { sorts[0] } +
            " • " +
            if (Credentials.homeView(activity) == Credentials.VIEW_DETAILED) "Detailed" else "Cards"
        if (sortViewAction.sub != sub) {
            val pos = actionsAdapter.indexOf(sortViewAction)
            if (pos >= 0) {
                sortViewAction = sortViewAction.copy(sub = sub)
                actionsAdapter.replace(pos, sortViewAction)
            }
        }
    }

    /** Live subtitle on the "Sync across devices" card. */
    private fun syncSyncCard() {
        val activity = activity ?: return
        val sub = if (Credentials.syncOn(activity)) {
            "On — continue on any device"
        } else {
            "Off — this device only"
        }
        if (syncAction.sub != sub) {
            val pos = actionsAdapter.indexOf(syncAction)
            if (pos >= 0) {
                syncAction = syncAction.copy(sub = sub)
                actionsAdapter.replace(pos, syncAction)
            }
        }
    }

    /**
     * Settings -> Sync across devices: pushes this device's prefs, My List
     * and watch progress into Saved Messages and pulls anything newer back,
     * so a video left half-watched on the TV resumes on the phone (and the
     * other way). Automatic sync is on by default; this also toggles it.
     */
    private fun showSyncDialog(activity: Activity) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Sync across devices")
                .setItems(
                    arrayOf(
                        "Sync now",
                        "Automatic sync: ${if (Credentials.syncOn(activity)) "On" else "Off"}",
                        "Close",
                    ),
                ) { _, which ->
                    when (which) {
                        0 -> {
                            Toast.makeText(activity, "Syncing via Saved Messages...", Toast.LENGTH_SHORT).show()
                            CloudBackup.restore(activity, force = true)
                            CloudBackup.request(activity, force = true)
                        }

                        1 -> {
                            Credentials.setSyncOn(activity, !Credentials.syncOn(activity))
                            syncSyncCard()
                        }

                        else -> return@setItems
                    }
                }
                .show()
        }
    }

    private fun subsLanguagesInput(activity: Activity) {        val input = android.widget.EditText(activity).apply {
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
        val chatRows = collectChats()
            .mapNotNull { chat ->
                val videos = videosFor(chat)
                if (videos.isEmpty()) null else VideoRow.from(chat, videos, hasMore = false)
            }
        // "Continue Watching" leads the library (after search), Netflix style
        continueRow(chatRows.flatMap { it.items })?.let { rows.add(it) }
        // The whole watchlist leads the screen like a Netflix "My List" row
        val saved = Watchlist.all(activity)
        if (saved.isNotEmpty()) {
            rows.add(VideoRow(-1L, "My List", saved, hasMore = false))
        }
        rows.addAll(chatRows)
        paintRows(activity, rows, emptyState = true)
    }

    /**
     * "Continue Watching": titles with resumable local progress (played
     * past 10 s, not yet at the 97% watched mark), most recently watched
     * first - the phone app's row, ported. Progress entries that reference
     * titles absent from this scan (fresh sync bookmark whose video has not
     * been indexed yet) are skipped until the scan picks them up.
     */
    private fun continueRow(allItems: List<VideoItem>): VideoRow? {
        val context = context ?: return null
        val progress = VideoLibrary.progressAll(context)
        progressMap = progress
        if (progress.isEmpty()) return null
        val byKey = allItems.associateBy { "${it.chatId}:${it.messageId}" }
        val stamp = HashMap<VideoItem, Long>()
        val items = progress.entries.mapNotNull { (key, p) ->
            val (posMs, durMs, updatedMs) = p
            if (posMs <= 10_000L) return@mapNotNull null
            if (durMs > 0L && posMs >= durMs * 97 / 100) return@mapNotNull null
            val item = byKey[key] ?: return@mapNotNull null
            stamp[item] = updatedMs
            item
        }.sortedByDescending { stamp[it] ?: 0L }.take(12)
        if (items.isEmpty()) return null
        return VideoRow(-3L, "Continue Watching", items, hasMore = false)
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
                val presenter = when {
                    // Continue cards carry art + progress bar regardless of view mode
                    row.chatId == -3L -> continueCardPresenter
                    Credentials.homeView(activity) == Credentials.VIEW_DETAILED -> detailRowPresenter
                    else -> videoCardPresenter
                }
                val cards = ArrayObjectAdapter(presenter)
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
        val chatRows = VideoLibrary.chatIds(context).mapNotNull { (id, title, _) ->
            val items = sortItems(VideoLibrary.chatItems(context, id))
            if (items.isEmpty()) null else VideoRow(id, title.ifBlank { "Chat $id" }, items, hasMore = false)
        }
        continueRow(chatRows.flatMap { it.items })?.let { rows.add(it) }
        val saved = Watchlist.all(activity)
        if (saved.isNotEmpty()) {
            rows.add(VideoRow(-1L, "My List", saved, hasMore = false))
        }
        rows.addAll(chatRows)
        return rows
    }

    /** Orders a row's videos per Settings -> Sort & view. */
    private fun sortItems(items: List<VideoItem>): List<VideoItem> {
        val context = context ?: return items
        return when (Credentials.homeSort(context)) {
            Credentials.SORT_OLDEST -> items.sortedBy { it.messageId }
            Credentials.SORT_TITLE -> items.sortedBy { it.title.lowercase() }
            Credentials.SORT_LARGEST -> items.sortedByDescending { it.fileSize }
            else -> items.sortedByDescending { it.messageId }
        }
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
            // Messages chat id IS the user's own id; ownChat() waits out
            // the fresh-login race where GetChat still says "not found".
            val saved = TdLib.ownChat() ?: return emptyList()
            val chats = mutableListOf(saved)
            val savedId = saved.id
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
     * Settings -> Add chats: a searchable multi-select of every chat this
     * session knows (chat list for user sessions, chat cache + local index
     * for bots). Pinned chats sit at the top, the rest follows chat recency;
     * typing in the search box narrows the list live. Titles come from the
     * live cache, falling back to the local index so never-opened chats
     * still read sensibly.
     */
    private fun showLibraryChatsDialog(activity: Activity) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val isUser = TdLib.ensureMe()
            if (isUser) TdLib.loadChatsBlocking()
            val context = context
            val storedTitles = context
                ?.let { VideoLibrary.chatIds(it).associate { (id, title, _) -> id to title } }
                ?: emptyMap()
            // Newest activity per chat: live last message, else the newest
            // video seen in the local index - recency order for both.
            val recency = context
                ?.let { VideoLibrary.chatIds(it).associate { (id, _, newest) -> id to newest } }
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
            if (candidates.isEmpty()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "No chats seen yet - press Refresh first", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val pinned = Credentials.libraryChats(activity).toMutableSet()
            // Pinned chats lead (they are the active library), then recency
            val ordered = candidates.sortedWith(
                compareByDescending<TdApi.Chat> { it.id.toString() in pinned }
                    .thenByDescending { it.lastMessage?.date ?: recency[it.id] ?: 0 }
                    .thenBy { it.title.lowercase() },
            )
            activity.runOnUiThread {
                val container = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                }
                val search = android.widget.EditText(activity).apply {
                    hint = "Search chats..."
                    setSingleLine()
                }
                val list = android.widget.ListView(activity).apply {
                    choiceMode = android.widget.AbsListView.CHOICE_MODE_MULTIPLE
                }
                container.addView(
                    search,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                container.addView(
                    list,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )

                fun render(query: String) {
                    val filtered = ordered.filter {
                        it.title.lowercase().contains(query.trim().lowercase())
                    }
                    list.tag = filtered
                    list.adapter = android.widget.ArrayAdapter(
                        activity,
                        android.R.layout.simple_list_item_multiple_choice,
                        filtered.map { it.title.ifBlank { "Chat ${it.id}" } },
                    )
                    filtered.indices.forEach { i ->
                        list.setItemChecked(i, filtered[i].id.toString() in pinned)
                    }
                }
                render("")
                search.addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(s: android.text.Editable?) =
                            render(s?.toString() ?: "")
                    },
                )
                list.setOnItemClickListener { _, _, which, _ ->
                    val filtered = list.tag as? List<TdApi.Chat> ?: return@setOnItemClickListener
                    val chat = filtered.getOrNull(which) ?: return@setOnItemClickListener
                    val id = chat.id.toString()
                    if (id in pinned) pinned.remove(id) else pinned.add(id)
                    list.setItemChecked(which, id in pinned)
                }

                AlertDialog.Builder(activity)
                    .setTitle("Library chats")
                    .setView(container)
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
        // Sorted per Settings -> Sort & view, hidden entries filtered out
        return sortItems(
            scanned
                .distinctBy { it.chatId to it.messageId }
                .filter { !VideoLibrary.isHidden(context, it.chatId, it.messageId) },
        ).take(LIMIT + 40)
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
        const val ACTION_SORT_VIEW = 11
    const val ACTION_APP_ICON = 12

        /** Set when a Remove happens elsewhere; guarantees a row rebuild. */
        var forceRebuild = false

        /** The silent update check runs once per process, not per fragment. */
        var autoUpdateChecked = false

        /** The cloud-backup restore runs once per process. */
        var cloudRestored = false
    }
}