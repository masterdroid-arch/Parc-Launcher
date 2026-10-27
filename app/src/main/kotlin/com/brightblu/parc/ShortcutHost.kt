package com.brightblu.parc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import com.brightblu.parc.model.AppId

/**
 * Shortcut launch/host boundary owned by `:app`. The contract's [com.brightblu.parc.model.AppIndex.shortcuts]
 * already reads shortcuts through `:core:data`; launching one and answering "am I the default home?" is
 * done here against the system LauncherApps, so `:core:data` stays untouched by the long-press menu.
 */
interface ShortcutHost {
    fun isDefaultHome(): Boolean
    fun launchShortcut(appId: AppId, shortcutId: String)
}

internal class LauncherShortcutHost(context: Context) : ShortcutHost {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    override fun isDefaultHome(): Boolean = launcherApps.hasShortcutHostPermission()

    override fun launchShortcut(appId: AppId, shortcutId: String) {
        // "pkg/pkg.Class@serial" -> package name, matching the AppId flattening in :core:data.
        val component = appId.value.substringBefore('@')
        val packageName = component.substringBefore('/')
        try {
            launcherApps.startShortcut(packageName, shortcutId, null, null, Process.myUserHandle())
        } catch (_: SecurityException) {
            // Parc is not the default home; the menu would not have offered the shortcut.
        } catch (_: IllegalStateException) {
            // The shortcut was removed while the menu was open.
        } catch (_: ActivityNotFoundException) {
            // The shortcut's intent can no longer be resolved.
        } catch (_: IllegalArgumentException) {
            // Malformed package or shortcut id.
        }
    }
}
