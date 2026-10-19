package com.tgtv.videotv.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import com.tgtv.videotv.model.VideoMapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TdLib {
    private const val TAG = "TdLib"

    /** Hard cap on the TDLib media cache (tdlib_files), ~2 GB. */
    const val CACHE_CAP_BYTES = 2L * 1024L * 1024L * 1024L

    /** After cleanup TDLib trims until the cache is 80% of the cap, leaving headroom. */
    private const val CACHE_TARGET_BYTES = (2L * 1024L * 1024L * 1024L) * 3L / 4L

    private var client: Client? = null
    private val listeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var filesRoot: File? = null
    private val capRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---------------- Playback stream cache janitor ----------------

    /**
     * fileId currently being streamed by the player, or null. Set at player
     * create time, invalidated when the player leaves the video.
     */
    @Volatile
    var activeStreamFileId: Int? = null

    private var purgeJob: Job? = null

    /**
     * Cache purge with grace period: [delayMs] (20 s) after the user exits a
     * video stream, cancel still-pending downloads of that file and delete its
     * TDLib file-cache entry so the stream cache cannot grow unbounded.
     * If the same video has been started again within the window the purge is
     * skipped, so instant replays still benefit from the cached bytes.
     */
    fun scheduleStreamCachePurge(fileId: Int, delayMs: Long = 20_000L) {
        if (!isStarted) return
        purgeJob?.cancel()
        purgeJob = appScope.launch {
            delay(delayMs)
            if (activeStreamFileId == fileId) return@launch
            try {
                send(TdApi.CancelDownloadFile(fileId, true))
            } catch (_: TdError) {
            }
            try {
                send(TdApi.DeleteFile(fileId))
            } catch (e: TdError) {
                Log.e(TAG, "cache purge DeleteFile failed: ${e.message}")
            }
            Log.i(TAG, "cache purged for file $fileId")
            enforceCacheCap()
        }
    }

    /**
     * Keeps the TDLib media cache under [CACHE_CAP_BYTES] (2 GB). When the
     * byte size of the cache directory exceeds the cap, TDLib's
     * OptimizeStorage deletes the least-recently used files until the cache
     * falls back to [CACHE_TARGET_BYTES]. Skipped while a video is streaming.
     */
    fun enforceCacheCap() {
        val root = filesRoot ?: return
        if (!isStarted) return
        if (activeStreamFileId != null) return
        if (!capRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val used = dirSize(root)
                if (used <= CACHE_CAP_BYTES) return@launch
                Log.i(TAG, "cache cap exceeded: %d bytes -> optimizing".format(used))
                send(
                    TdApi.OptimizeStorage(
                        CACHE_TARGET_BYTES,
                        -1,
                        -1,
                        -1,
                        emptyArray<TdApi.FileType>(),
                        LongArray(0),
                        LongArray(0),
                        false,
                        -1,
                    ),
                )
                val after = dirSize(root)
                Log.i(TAG,
                    "cache cap optimization done: %d -> %d bytes".format(used, after))
            } catch (e: TdError) {
                Log.e(TAG, "cache cap OptimizeStorage failed: ${e.message}")
            } finally {
                capRunning.set(false)
            }
        }
    }

    private fun dirSize(root: File): Long =
        root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /**
     * Logs the TDLib cache size every 15 s while a video is streaming, so
     * stutter reports can be checked against cache growth ("is the cache
     * leaking?"). Cheap: one walk of the cache dir per interval.
     */
    fun startCacheMonitor() {
        appScope.launch {
            while (isStarted) {
                if (activeStreamFileId != null) {
                    val root = filesRoot
                    val mb = root?.let { dirSize(it) }?.ushr(20) ?: 0L
                    Log.i(TAG, "cache monitor: streaming, tdlib_files = ${mb}MB")
                }
                delay(15_000L)
            }
        }
    }

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState.asStateFlow()

    private val _authErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val authErrors = _authErrors.asSharedFlow()

    /**
     * Telegram phone-app link shown as a QR code while device-link (QR) login
     * is in progress; null when no such QR is being displayed.
     */
    private val _qrLink = MutableStateFlow<String?>(null)
    val qrLink: StateFlow<String?> = _qrLink.asStateFlow()

    val latestFiles = ConcurrentHashMap<Int, TdApi.File>()

    /**
     * Chats seen through TDLib updates. Bots can't call LoadChats/GetChats in
     * TDLib 1.8+ ("method is not available to bots"), so the session's chat list
     * is built from UpdateNewChat / UpdateChatTitle instead.
     */
    val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    /**
     * Video messages captured live per chat. Bots cannot read history in
     * TDLib 1.8+ (GetChatHistory / SearchChatMessages are user-only), so a bot
     * session sees only messages posted while it is connected.
     */
    val liveMessages = ConcurrentHashMap<Long, MutableList<TdApi.Message>>()

    /**
     * Pulls the user's main chat list page by page via LoadChats (user
     * sessions only - bots cannot call it). Loaded chats stream in as
     * UpdateNewChat and land in [chatCache]. Stops when TDLib reports the
     * list is exhausted or after a hard cap of 5 000 chats.
     */
    suspend fun loadChatsBlocking(limit: Int = 100) {
        if (!isStarted) return
        repeat(50) {
            try {
                send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
            } catch (_: TdError) {
                return
            }
        }
    }

    /**
     * The authorized account (fetched with GetMe after Ready). Lets the UI
     * trust the live session type instead of the login-mode pref, which can
     * go stale when pairing via phone QR without passing LoginActivity.
     */
    @Volatile
    var me: TdApi.User? = null
        private set

    /** True when the live TDLib session is a real user account (not a bot). */
    val sessionIsUser: Boolean get() = me?.type is TdApi.UserTypeRegular

    /**
     * Resolves [me] with GetMe if needed and returns [sessionIsUser]. Cheap
     * once resolved; call before branching on bot/user behaviour.
     */
    suspend fun ensureMe(): Boolean {
        if (me != null) return sessionIsUser
        if (!isStarted) return false
        me = runCatching { send(TdApi.GetMe()) as? TdApi.User }.getOrNull()
        return sessionIsUser
    }

    private var authJob: Job? = null
    private var sentParameters = false

    val isStarted: Boolean get() = client != null

    fun start(context: Context) {
        if (client != null) return
        try {
            Client.setLogMessageHandler(1) { _, _ -> }
            client = Client.create(
                { obj -> dispatchUpdate(obj) },
                { _ -> Unit },
                { t -> Log.e(TAG, "tdlib exception", t) },
            )
        } catch (t: Throwable) {
            // UnsatisfiedLinkError etc: leave UI usable, send() will surface the failure
            Log.e(TAG, "TDLib client creation failed", t)
            return
        }
        client?.send(TdApi.SetLogVerbosityLevel(1), null)
        // This app runs on Wi-Fi / Ethernet TVs
        client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeWiFi()), null)
        // Make sure the database dirs exist
        File(context.filesDir, "tdlib").mkdirs()
        filesRoot = File(context.filesDir, "tdlib_files").apply { mkdirs() }
        // Bots receive chats they were added to without any extra init
        _authState.value = null
        _qrLink.value = null
        chatCache.clear()
        authJob?.cancel()
        sentParameters = false
        authJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            authState.collect { st ->
                when (st) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> if (!sentParameters) {
                        sentParameters = true
                        setTdlibParametersBlocking(context)
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        val tok = Credentials.botToken(context).orEmpty()
                        if (tok.isBlank()) {
                            // No bot token: fall back to QR ("link desktop device") login
                            runCatching { send(TdApi.RequestQrCodeAuthentication()) }
                                .onFailure { error ->
                                    _authErrors.tryEmit("QR login failed to start: ${error.message}")
                                }
                        } else {
                            // Bots authorize with a token straight from this state
                            runCatching { send(TdApi.CheckAuthenticationBotToken(tok)) }
                                .onFailure { error ->
                                    _authErrors.tryEmit(error.message ?: "Invalid bot token")
                                }
                        }
                    }
                    is TdApi.AuthorizationStateReady -> {
                        authJob?.cancel()
                        // Identify the session (bot vs user) for UI branching
                        appScope.launch {
                            runCatching { ensureMe() }
                            // Trim the cache back under the 2 GB right after login
                            delay(3_000L)
                            enforceCacheCap()
                        }
                        startCacheMonitor()
                    }
                    else -> Unit
                }
            }
        }
    }

    fun stop() {
        authJob?.cancel()
        authJob = null
    }

    fun log(message: String) {
        Log.i(TAG, message)
    }

    /** Fire-and-forget send (results/updates still flow through [dispatchUpdate]). */
    fun fire(function: TdApi.Function<*>) {
        client?.send(function, Client.ResultHandler { })
    }

    fun addListener(listener: (TdApi.Object) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (TdApi.Object) -> Unit) = listeners.remove(listener)

    private fun dispatchUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> {
                val st = o.authorizationState
                _authState.value = st
                when (st) {
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                        _qrLink.value = st.link
                    is TdApi.AuthorizationStateReady ->
                        _qrLink.value = null
                    else -> Unit
                }
            }
            is TdApi.UpdateFile -> latestFiles[o.file.id] = o.file
            is TdApi.UpdateNewChat -> {
                o.chat.title?.let { chatCache[o.chat.id] = o.chat }
            }
            is TdApi.UpdateChatTitle -> chatCache[o.chatId]?.let { it.title = o.title }
            is TdApi.UpdateChatLastMessage -> chatCache[o.chatId]?.also { c -> o.lastMessage?.let { c.lastMessage = it } }
            is TdApi.UpdateNewMessage -> if (VideoMapper.isVideoMessage(o.message.content)) {
                liveMessages
                    .getOrPut(o.message.chatId) { CopyOnWriteArrayList() }
                    .add(o.message)
            }
            else -> Unit
        }
        listeners.forEach { l -> runCatching { l(o) } }
    }

    private fun setTdlibParametersBlocking(context: Context) {
        val p = TdApi.SetTdlibParameters(
            false,
            File(context.filesDir, "tdlib").absolutePath,
            File(context.filesDir, "tdlib_files").absolutePath,
            null,                       // no database encryption
            true, true, true, false,    // file / chat info / message databases
            Credentials.apiId(context),
            Credentials.apiHash(context).orEmpty(),
            "en-US", "TGTV", "Android TV", "1.0",
        )
        client?.send(p) { result ->
            if (result is TdApi.Error) {
                _authErrors.tryEmit("TDLib init failed: ${result.message}")
            }
        }
    }

    suspend fun checkBotToken(token: String) {
        runCatching { send(TdApi.CheckAuthenticationBotToken(token)) }
            .onFailure { error -> _authErrors.emit(error.message ?: "Invalid bot token") }
    }

    /**
     * Ends the current session (bot or user) so the client can switch to another
     * login type. Errors when TDLib is not yet authorized; callers ignore them.
     */
    suspend fun logOut() {
        _qrLink.value = null
        runCatching { send(TdApi.LogOut()) }
    }

    /**
     * Closes the client and deletes the whole TDLib database. Needed when
     * switching login types (bot -> user/QR): TDLib only allows one session per
     * client and a stale bot session makes user-only methods fail.
     */
    suspend fun resetDb(context: Context) {
        authJob?.cancel()
        authJob = null
        _qrLink.value = null
        _authState.value = null
        latestFiles.clear()
        chatCache.clear()
        liveMessages.clear()
        me = null
        sentParameters = false
        val c = client
        client = null
        c?.let {
            Log.i(TAG, "resetDb: closing TDLib client")
            it.send(TdApi.Close(), Client.ResultHandler { r ->
                if (r is TdApi.Error) Log.e(TAG, "Close failed: ${r.code} ${r.message}")
                else Log.i(TAG, "resetDb: Close accepted")
            })
        }
        withContext(Dispatchers.IO) {
            // Let TDLib flush before deleting its files
            delay(800L)
            File(context.filesDir, "tdlib").deleteRecursively()
            File(context.filesDir, "tdlib_files").deleteRecursively()
            Log.i(TAG, "resetDb: db deleted")
        }
    }

    fun close() {
        client = null
    }

    /** Sends [function] and suspends until the result arrives; throws [TdError] on error responses. */
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val c = client ?: run {
                cont.resumeWithException(IllegalStateException("TDLib not started"))
                return@suspendCancellableCoroutine
            }
            c.send(function, Client.ResultHandler { obj ->
                if (obj is TdApi.Error) {
                    Log.e(TAG, "${function.javaClass.simpleName} failed: ${obj.code} ${obj.message}")
                    if (cont.isActive) cont.resumeWithException(TdError.from(obj))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    if (cont.isActive) cont.resume(obj as T)
                }
            })
        }

    // ---------------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------------

    /**
     * TDLib allows at most one pending range download per file: a new
     * [TdApi.DownloadFile] cancels the in-flight one. All range downloads
     * therefore go through a global mutex so concurrent loader threads
     * (ExoPlayer opens several DataSources at once) cannot cancel each other.
     */
    private val rangeDownloadMutex = Mutex()

    /** Synchronously downloads the byte range [offset, offset+limit) and returns once cached. */
    suspend fun downloadRange(
        fileId: Int,
        offset: Long,
        limit: Long,
        priority: Int = 32,
    ): TdApi.File = rangeDownloadMutex.withLock {
        send(TdApi.DownloadFile(fileId, priority, offset, limit, true))
    }

    /**
     * Downloads [fileId] fully and suspends until the local copy exists.
     * Uses [TdApi.DownloadFile] with default offset/limit (whole file).
     */
    suspend fun downloadFully(fileId: Int, priority: Int = 32): TdApi.File {
        // Fast path: already downloaded before
        latestFiles[fileId]?.let { existing ->
            if (existing.local?.isDownloadingCompleted == true) return existing
        }
        send(TdApi.DownloadFile(fileId, priority, 0, 0, false))
        return waitForFileUpdate(fileId) { file ->
            file.local?.isDownloadingCompleted == true
        }
    }

    /** Suspends until an update for [fileId] matching [predicate] arrives (or is already stored). */
    suspend fun waitForFileUpdate(
        fileId: Int,
        predicate: (TdApi.File) -> Boolean,
    ): TdApi.File {
        latestFiles[fileId]?.let { if (predicate(it)) return it }
        var resumed = false
        return suspendCancellableCoroutine { cont ->
            lateinit var listener: (TdApi.Object) -> Unit
        listener = { o ->
            if (o is TdApi.UpdateFile && o.file.id == fileId && predicate(o.file)) {
                listeners.remove(listener)
                if (!resumed && cont.isActive) {
                    resumed = true
                    cont.resume(o.file)
                }
            }
        }
        listeners.add(listener)
            cont.invokeOnCancellation { listeners.remove(listener) }
        }
    }
}
