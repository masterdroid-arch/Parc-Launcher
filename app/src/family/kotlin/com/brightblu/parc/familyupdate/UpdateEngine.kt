package com.brightblu.parc.familyupdate

import android.content.SharedPreferences
import com.brightblu.parc.ParcLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** The update settings and what the last check found. A private preferences file, not part of any backup. */
internal class UpdatePrefs(private val prefs: SharedPreferences) {
    /** Default ON. */
    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(value) { prefs.edit().putBoolean("autoCheck", value).apply() }

    /** When a check last got an answer. 0 = never. */
    var lastChecked: Long
        get() = prefs.getLong("lastChecked", 0L)
        set(value) { prefs.edit().putLong("lastChecked", value).apply() }

    /** When a check was last tried, answered or not. */
    var lastAttempt: Long
        get() = prefs.getLong("lastAttempt", 0L)
        set(value) { prefs.edit().putLong("lastAttempt", value).apply() }

    /** The newer version the last check found, such as `1.5.19`, or null. */
    var available: String?
        get() = prefs.getString("available", null)
        set(value) { prefs.edit().apply { if (value == null) remove("available") else putString("available", value) }.apply() }

    var laterVersion: String?
        get() = prefs.getString("laterVersion", null)
        set(value) { prefs.edit().apply { if (value == null) remove("laterVersion") else putString("laterVersion", value) }.apply() }

    /** The local day (epoch day) "Later" was tapped on. */
    var laterDay: Long
        get() = prefs.getLong("laterDay", -1L)
        set(value) { prefs.edit().putLong("laterDay", value).apply() }
}

/** What a person can see. [Failed] belongs to the update itself; a failed check is [UpdateUi.checkMessage]. */
internal sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val version: String, val installerOpened: Boolean = false) : UpdateState
    data class Downloading(val version: String, val fraction: Float?) : UpdateState
    data class Failed(val version: String, val problem: UpdateProblem) : UpdateState
}

internal data class UpdateUi(
    val state: UpdateState = UpdateState.Idle,
    val checking: Boolean = false,
    /** The result of the last manual or automatic check in words, or empty. */
    val checkMessage: String = "",
    val lastChecked: Long = 0L,
    val autoCheck: Boolean = true,
    /** True when "Later" has hidden the hub notice for today. The Settings row stays. */
    val hubHidden: Boolean = false,
) {
    /** The version being offered or worked on, or null. */
    val version: String? get() = when (val s = state) {
        is UpdateState.Available -> s.version
        is UpdateState.Downloading -> s.version
        is UpdateState.Failed -> s.version
        UpdateState.Idle -> null
    }
}

/**
 * T396: the update checker. One GET to the latest-release address, a version compare, and (only when the person taps
 * Update) a download of the APK and its checksum, a SHA-256 check, and a hand-off to Android's installer. Nothing is
 * installed without the person tapping and Android asking. Every failure ends in a plain message, never a crash.
 */
internal class UpdateEngine(
    private val prefs: UpdatePrefs,
    private val transport: UpdateTransport,
    private val installedVersion: String,
    private val cacheDir: File,
    private val latestUrl: () -> String,
    /** Opens Android's installer for the verified file; false when it could not be opened. */
    private val handOff: (File) -> Boolean,
    private val canInstall: () -> Boolean,
    private val askInstallPermission: () -> Unit,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val Tag = "Update"
        const val MaxJson = 1024 * 1024
        const val MaxChecksumText = 4 * 1024
        const val MaxApk = 300L * 1024 * 1024
    }

    private val mutable = MutableStateFlow(initial())
    val ui: StateFlow<UpdateUi> = mutable
    private var job: Job? = null

    private fun today(): Long = java.time.Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toEpochDay()

    private fun initial(): UpdateUi {
        val saved = prefs.available?.takeIf { UpdateVersion.isNewer(it, installedVersion) }
        if (saved == null && prefs.available != null) prefs.available = null
        return UpdateUi(
            state = saved?.let { UpdateState.Available(it) } ?: UpdateState.Idle,
            lastChecked = prefs.lastChecked, autoCheck = prefs.autoCheck,
            hubHidden = saved != null && !UpdateNotice.visible(saved, prefs.laterVersion, prefs.laterDay, today()),
        )
    }

    private fun busy() = mutable.value.checking || mutable.value.state is UpdateState.Downloading

    fun setAutoCheck(on: Boolean) {
        prefs.autoCheck = on
        mutable.update { it.copy(autoCheck = on) }
    }

    /** "Later": hides the hub notice for this version until tomorrow. Settings keeps its row. */
    fun later() {
        val version = mutable.value.version ?: return
        prefs.laterVersion = version
        prefs.laterDay = today()
        mutable.update { it.copy(hubHidden = true) }
    }

    /** The background check: only when the switch is on and the throttle allows. */
    suspend fun backgroundCheck() {
        if (!prefs.autoCheck) return
        if (!UpdateThrottle.due(clock(), prefs.lastChecked, prefs.lastAttempt)) return
        check()
    }

    /** From process start: a check in the background if the switch is on and a day has passed. Never blocks the caller. */
    fun checkInBackgroundIfDue() {
        if (busy()) return
        scope.launch { backgroundCheck() }
    }

    fun checkNow() {
        if (busy()) return
        job = scope.launch { check() }
    }

    /** One check. Never throws; the result is in [ui] and in the saved values. */
    suspend fun check() {
        if (busy()) return
        mutable.update { it.copy(checking = true, checkMessage = "") }
        prefs.lastAttempt = clock()
        val outcome = withContext(Dispatchers.IO) { fetchLatest() }
        when (outcome) {
            is Latest.Failed -> mutable.update { it.copy(checking = false, checkMessage = outcome.problem.message) }
            is Latest.Found -> {
                prefs.lastChecked = clock()
                val newer = UpdateVersion.isNewer(outcome.release.tag, installedVersion)
                val files = if (newer) UpdateAssets.choose(outcome.release) else null
                when {
                    !newer -> {
                        prefs.available = null
                        mutable.update { it.copy(checking = false, state = UpdateState.Idle, lastChecked = prefs.lastChecked,
                            checkMessage = "You have the latest version, $installedVersion.", hubHidden = false) }
                    }
                    files == null -> {
                        // A release exists but its APK or checksum is not attached yet: offer nothing.
                        prefs.available = null
                        mutable.update { it.copy(checking = false, state = UpdateState.Idle, lastChecked = prefs.lastChecked,
                            checkMessage = UpdateProblem.NoFiles.message) }
                    }
                    else -> {
                        val version = files.version.toString()
                        prefs.available = version
                        val hidden = !UpdateNotice.visible(version, prefs.laterVersion, prefs.laterDay, today())
                        mutable.update { it.copy(checking = false, state = UpdateState.Available(version), lastChecked = prefs.lastChecked,
                            checkMessage = UpdateText.available(version), hubHidden = hidden) }
                    }
                }
            }
        }
    }

    private sealed interface Latest {
        data class Found(val release: UpdateRelease) : Latest
        data class Failed(val problem: UpdateProblem) : Latest
    }

    private fun fetchLatest(): Latest = when (val result = transport.getText(latestUrl(), MaxJson)) {
        is TransportResult.Failed -> Latest.Failed(result.problem)
        TransportResult.Cancelled -> Latest.Failed(UpdateProblem.Offline)
        is TransportResult.Ok -> UpdateReleaseJson.parse(result.value)?.let { Latest.Found(it) } ?: Latest.Failed(UpdateProblem.BadAnswer)
    }

    /** The Update button (and Retry). Runs on the engine's own scope so leaving the screen does not stop it. */
    fun update() {
        if (busy()) return
        job = scope.launch { downloadAndInstall() }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Download, verify, hand off. The person's Cancel cancels this coroutine. */
    suspend fun downloadAndInstall() {
        val version = mutable.value.version ?: return
        if (busy()) return
        if (!canInstall()) {
            askInstallPermission()
            mutable.update { it.copy(state = UpdateState.Failed(version, UpdateProblem.NeedInstallPermission)) }
            return
        }
        mutable.update { it.copy(state = UpdateState.Downloading(version, null)) }
        try {
            val problem = withContext(Dispatchers.IO) { run(version) }
            mutable.update {
                it.copy(state = if (problem == null) UpdateState.Available(version, installerOpened = true) else UpdateState.Failed(version, problem))
            }
        } catch (e: CancellationException) {
            cleanUp()
            mutable.update { it.copy(state = UpdateState.Available(version)) }
            throw e
        }
    }

    private suspend fun run(version: String): UpdateProblem? {
        val active = currentCoroutineContext()
        // A fresh answer at the moment of the tap, so the files named are the ones published now.
        val release = when (val latest = fetchLatest()) {
            is Latest.Failed -> return latest.problem
            is Latest.Found -> latest.release
        }
        if (!UpdateVersion.isNewer(release.tag, installedVersion)) return UpdateProblem.BadAnswer
        val files = UpdateAssets.choose(release) ?: return UpdateProblem.NoFiles
        if (files.version.toString() != version) mutable.update { it.copy(state = UpdateState.Downloading(files.version.toString(), null)) }

        val checksumText = when (val r = transport.getText(files.checksum.url, MaxChecksumText)) {
            is TransportResult.Failed -> return r.problem
            TransportResult.Cancelled -> return null
            is TransportResult.Ok -> r.value
        }
        val expected = UpdateChecksum.expected(checksumText, files.apk.name) ?: return UpdateProblem.BadAnswer

        val dir = File(cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, files.apk.name + ".part")
        val download = transport.download(files.apk.url, part, MaxApk, { !active.isActive }) { done, total ->
            val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
            mutable.update { s -> if (s.state is UpdateState.Downloading) s.copy(state = UpdateState.Downloading(s.version.orEmpty(), fraction)) else s }
        }
        when (download) {
            is TransportResult.Failed -> { part.delete(); return download.problem }
            TransportResult.Cancelled -> { part.delete(); throw CancellationException("cancelled by the person") }
            is TransportResult.Ok -> Unit
        }
        val actual = part.inputStream().use { UpdateChecksum.hex(it) }
        if (!UpdateChecksum.matches(actual, expected)) {
            part.delete()
            ParcLog.w(Tag, "checksum mismatch")
            return UpdateProblem.BadChecksum
        }
        val apk = File(dir, files.apk.name)
        apk.delete()
        if (!part.renameTo(apk)) return UpdateProblem.NoSpace
        return if (handOff(apk)) null else UpdateProblem.CannotOpenInstaller
    }

    private fun cleanUp() {
        File(cacheDir, "updates").listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
