package com.tgtv.videotv.ota

import android.content.Context
import android.os.Build
import com.tgtv.videotv.td.Credentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide OTA update state (ported from the phone app, minus the
 * foreground service: on the TV the progress dialog stays on screen while an
 * application-scoped coroutine streams the APK, so no notification is needed).
 * Deliberately decoupled from the Fragment: the check and download run on
 * their own scope, so leaving the browse screen never cancels an in-flight
 * update. [MainBrowseFragment] collects [state] and renders it.
 */
object UpdateManager {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

    data class UpdateState(
        val phase: Phase = Phase.IDLE,
        val tag: String = "",
        /** Download progress, 0..100 (only meaningful while DOWNLOADING). */
        val progress: Int = 0,
        /** True when the offered release is a pre-release (beta opt-in). */
        val prerelease: Boolean = false,
    )

    /** Single source of truth; the UI collects this. */
    val state = MutableStateFlow(UpdateState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The release backing the current AVAILABLE/DOWNLOADING state. */
    private var release: Updater.Release? = null

    /**
     * Set when the last install attempt stopped at the "Install unknown
     * apps" page. Once the user grants it there, [resumeInstall] launches
     * the installer automatically - no second tap needed - and the granted
     * permission then persists for all future updates.
     */
    var awaitingInstallPermission = false
        private set

    /**
     * Polls GitHub for the latest release (stable, or the newest
     * beta when the user opted in). No-op while a check/download runs.
     */
    fun check(context: Context, betas: Boolean? = null) {
        if (state.value.phase == Phase.CHECKING || state.value.phase == Phase.DOWNLOADING) return
        val app = context.applicationContext
        state.value = UpdateState(Phase.CHECKING)
        scope.launch {
            val useBetas = betas ?: Credentials.betaUpdates(app)
            // Bounded: without this the state could stick at CHECKING forever
            // on a wedged network and the UI would never report failure.
            val rel = withTimeoutOrNull(30_000L) {
                Updater.fetchLatest(useBetas)
            }
            release = rel
            state.value = when {
                rel == null -> UpdateState(Phase.FAILED)
                // Newer release AND the APK for it is already fully
                // downloaded (e.g. the install was interrupted or the
                // process died after the download): go straight to READY
                // instead of making the user download it again.
                Updater.isNewer(rel.tag, Updater.installedVersion(app)) ->
                    if (apkMatches(app, rel)) {
                        UpdateState(Phase.READY, rel.tag, 100, rel.prerelease)
                    } else {
                        UpdateState(Phase.AVAILABLE, rel.tag, prerelease = rel.prerelease)
                    }

                else -> {
                    // Stale APK from an old release would only waste space.
                    Updater.clearDownload(app)
                    UpdateState(Phase.UP_TO_DATE, rel.tag)
                }
            }
        }
    }

    /** Streams the available release APK to the cache; state reports progress. */
    fun download(context: Context) {
        if (state.value.phase != Phase.AVAILABLE) return
        val rel = release ?: return
        val app = context.applicationContext
        if (apkMatches(app, rel)) {
            state.value = UpdateState(Phase.READY, rel.tag, 100, rel.prerelease)
            return
        }
        state.value = UpdateState(Phase.DOWNLOADING, rel.tag, 0, rel.prerelease)
        scope.launch {
            val file = Updater.download(app, rel) { pct ->
                state.value = UpdateState(Phase.DOWNLOADING, rel.tag, pct, rel.prerelease)
            }
            state.value = if (file != null) {
                UpdateState(Phase.READY, rel.tag, 100, rel.prerelease)
            } else {
                UpdateState(Phase.FAILED, rel.tag, prerelease = rel.prerelease)
            }
        }
    }

    /**
     * Launches the system installer for the downloaded APK. On any failure
     * the state stays READY and the APK is kept - the user retries the
     * install only, never the download.
     */
    fun install(context: Context): Updater.InstallResult {
        val file = Updater.apkFile(context)
        if (!file.exists()) return Updater.InstallResult.NO_APK
        return when (val res = Updater.install(context, file)) {
            Updater.InstallResult.LAUNCHED -> {
                awaitingInstallPermission = false
                state.value = UpdateState(Phase.IDLE)
                res
            }

            Updater.InstallResult.NEEDS_PERMISSION -> {
                awaitingInstallPermission = true
                res
            }

            // FAILED: keep state READY + APK on disk so only the install is
            // retried - never the download.
            else -> res
        }
    }

    /**
     * Called from onResume: the moment the user grants "Install unknown
     * apps" (after the settings page we opened), the installer is launched
     * automatically. Only fires when the permission is actually granted now,
     * so coming back without granting never loops.
     */
    fun resumeInstall(context: Context) {
        if (!awaitingInstallPermission || state.value.phase != Phase.READY) return
        val app = context.applicationContext
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            app.packageManager.canRequestPackageInstalls()
        if (!granted) return
        awaitingInstallPermission = false
        install(app)
    }

    /** Drops a finished or broken download so a later check starts clean. */
    fun reset(context: Context) {
        Updater.clearDownload(context)
        state.value = UpdateState()
    }

    /** True when the APK on disk exists and matches [rel]'s size exactly. */
    private fun apkMatches(context: Context, rel: Updater.Release): Boolean {
        val f = Updater.apkFile(context)
        return rel.sizeBytes > 0 && f.exists() && f.length() == rel.sizeBytes
    }
}
