package com.brightblu.parc

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/**
 * Platform actions the long-press menus offer (T30). Kept behind an interface so `LauncherSession`
 * stays free of `Context` and the menu rows can be unit-tested with a recorder.
 */
interface AppActions {
    /** Opens the system app-info page for [entry]. */
    fun openAppInfo(entry: AppEntry)

    /** Opens the system uninstall flow for [entry] (the OS shows its own confirmation step). */
    fun uninstall(entry: AppEntry)

    /**
     * Whether the platform can uninstall [entry] (T68): a user app, or a system app that has been
     * updated at least once. A preinstalled system app without an update lives in the system image.
     */
    fun canUninstall(entry: AppEntry): Boolean

    /** Opens the top-level Android Settings page. */
    fun openAndroidSettings()
}

/** Production [AppActions] over the system Settings/intent surfaces. No permission required. */
class LauncherAppActions(private val context: Context) : AppActions {
    override fun openAppInfo(entry: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun uninstall(entry: AppEntry) {
        // ACTION_DELETE with a package URI shows the platform's own confirmation screen. It is offered
        // only for user apps; the intent is still guarded in case the platform refuses it.
        val intent = Intent(if (BuildConfig.FLAVOR == "play") android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    override fun canUninstall(entry: AppEntry): Boolean =
        canOfferUninstall(entry.isSystem, updatedSystemApp(entry.packageName))

    /** FLAG_UPDATED_SYSTEM_APP says the app was installed or updated on top of the system image. */
    private fun updatedSystemApp(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }.getOrDefault(false)

    override fun openAndroidSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/** Resolves an [AppId] to its installed [AppEntry]. The menu shows a row only when one is found. */
internal fun appEntryFor(id: AppId, entries: List<AppEntry>): AppEntry? = entries.firstOrNull { it.id == id }

/** Default no-op used by fixtures that only need the pure menu state. */
internal object NoOpAppActions : AppActions {
    override fun openAppInfo(entry: AppEntry) = Unit
    override fun uninstall(entry: AppEntry) = Unit
    override fun canUninstall(entry: AppEntry) = true
    override fun openAndroidSettings() = Unit
}
