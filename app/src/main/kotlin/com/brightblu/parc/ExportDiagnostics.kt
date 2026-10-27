package com.brightblu.parc

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.ring.SoundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * T88: what the Settings list needs to show the diagnostics row, same idea as [FeelSettingsEntry].
 * The Settings owner builds a row from these three fields and shows [DiagnosticsExport] under it or
 * on its own page; nothing else is required.
 */
internal data class DiagnosticsEntry(val id: String, val title: String, val description: String) {
    companion object {
        val Default = DiagnosticsEntry(
            "diagnostics_export",
            "Export diagnostics log",
            "Saves recent Parc messages and the last crash to a text file you can send. Nothing leaves the phone by itself.",
        )
    }
}

/** Facts for the top of the export. Plain values, no personal data. Immutable. */
internal data class DiagnosticsHeader(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val densityDpi: Int,
    /** Feature name to on/off, in the order shown. */
    val features: List<Pair<String, Boolean>>,
    /** Permission name to a short state such as `granted`, in the order shown. */
    val permissions: List<Pair<String, String>>,
)

/** Pure text building for the export; the collection of the facts is in [DiagnosticsCollector]. */
internal object ExportDiagnostics {
    const val FileName = "parc-log.txt"
    const val MimeType = "text/plain"

    fun header(h: DiagnosticsHeader, generatedAtMillis: Long): String = buildString {
        append("Parc diagnostics\n")
        append("generated: ").append(Instant.ofEpochMilli(generatedAtMillis)).append(" UTC\n")
        append("app: ").append(clean(h.appVersionName)).append(" (").append(h.appVersionCode).append(")\n")
        append("android: ").append(clean(h.androidRelease)).append(" (API ").append(h.sdkInt).append(")\n")
        append("device: ").append(clean(h.deviceModel)).append('\n')
        append("screen: ").append(h.screenWidthDp).append(" x ").append(h.screenHeightDp)
            .append(" dp, ").append(h.densityDpi).append(" dpi\n")
        append("features:\n")
        for ((name, on) in h.features) append("  ").append(clean(name)).append(": ").append(if (on) "on" else "off").append('\n')
        append("permissions:\n")
        for ((name, state) in h.permissions) append("  ").append(clean(name)).append(": ").append(clean(state)).append('\n')
    }

    /**
     * The whole file: header, the buffered log, the last crash and the T83 scene diagnostics.
     * [droppedLines] is how many older lines the ring pushed out.
     */
    fun build(
        header: DiagnosticsHeader,
        generatedAtMillis: Long,
        logLines: List<String>,
        droppedLines: Long,
        lastCrash: String?,
        scene: SceneDiagnostic?,
    ): String = buildString {
        append(header(header, generatedAtMillis))
        append("\n== log (").append(logLines.size).append(" lines")
        if (droppedLines > 0) append(", ").append(droppedLines).append(" older dropped")
        append(", times in UTC) ==\n")
        if (logLines.isEmpty()) append("(empty)\n") else logLines.forEach { append(it).append('\n') }
        append("\n== last crash ==\n")
        append(lastCrash?.trimEnd() ?: "none recorded").append('\n')
        append("\n== scene diagnostics ==\n")
        if (scene == null || scene.updatedAtMillis == 0L) {
            append("none recorded\n")
        } else {
            sceneDiagnosticLines(scene).forEach { append(it).append('\n') }
            append("Updated: ").append(Instant.ofEpochMilli(scene.updatedAtMillis)).append(" UTC\n")
        }
    }

    private fun clean(text: String): String = text.replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ").take(120)
}

/** Reads the live facts for [ExportDiagnostics.build] from the platform and the app-local stores. */
internal object DiagnosticsCollector {

    suspend fun text(context: Context, now: Long = System.currentTimeMillis()): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        ExportDiagnostics.build(
            header = header(app),
            generatedAtMillis = now,
            logLines = ParcLog.lines(),
            droppedLines = ParcLog.dropped(),
            lastCrash = runCatching { CrashGuard(app).lastTrace() }.getOrNull(),
            scene = runCatching { SceneDiagnosticsStore(app).last.first() }.getOrNull(),
        )
    }

    private suspend fun header(app: Context): DiagnosticsHeader {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val metrics = app.resources.displayMetrics
        val config = app.resources.configuration
        val settings = runCatching { ParcPrefs.create(app).settings.first() }.getOrNull()
        val comms = runCatching { ParcPrefs.create(app).commsEnabled.first() }.getOrNull()
        val look = runCatching { ParcLookStore(app).look.first() }.getOrNull()
        val feel = runCatching { FeelStore(app).prefs.first() }.getOrNull()
        val ui = runCatching { ParcUiPrefs(app) }.getOrNull()
        val features = buildList {
            settings?.let {
                add("clock" to it.clockVisible)
                add("stats hub" to it.statsVisible)
                add("haptics" to (it.haptics != HapticsLevel.Off))
            }
            comms?.let { add("comms" to it) }
            ui?.let {
                add("joystick aim" to it.joystickAim())
                add("hub double tap" to it.hubDoubleTap())
                add("notification badges" to it.notificationBadges())
                add("align across arcs" to it.alignAcrossArcs())
                add("mark work apps" to it.markWorkApps())
            }
            look?.let { add("date under clock" to it.showDate) }
            feel?.let { add("sound theme" to (it.sound != SoundTheme.Off)) }
        }
        return DiagnosticsHeader(
            appVersionName = info?.versionName ?: "unknown",
            appVersionCode = info?.longVersionCode ?: -1L,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            densityDpi = metrics.densityDpi,
            features = features,
            permissions = permissions(app),
        )
    }

    private fun permissions(app: Context): List<Pair<String, String>> = listOf(
        "usage access" to state { usageAccess(app) },
        "notification access" to state { notificationAccess(app) },
        "contacts" to state {
            app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        },
        "default home app" to state {
            app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        },
    )

    private fun state(check: () -> Boolean): String =
        try { if (check()) "granted" else "not granted" } catch (_: Throwable) { "unknown" }

    private fun usageAccess(app: Context): Boolean {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun notificationAccess(app: Context): Boolean =
        app.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(app, ParcNotificationListenerService::class.java)) == true
}
