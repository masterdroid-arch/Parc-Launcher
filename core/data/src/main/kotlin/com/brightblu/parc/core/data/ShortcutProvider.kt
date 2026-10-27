package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppShortcut

/**
 * Reads an app's shortcuts, capped at [MaxShortcuts]. Shortcuts only exist for the default home
 * app, so a missing host permission or a SecurityException yields an empty list rather than a
 * crash. Pure apart from the injected source.
 */
internal class ShortcutProvider(private val source: LauncherSource) {
    fun shortcuts(id: AppId): List<AppShortcut> {
        if (!source.hasShortcutHostPermission()) return emptyList()
        val shortcuts = try {
            source.shortcuts(id.flattenedComponent(), id.userSerial())
        } catch (e: SecurityException) {
            return emptyList()
        }
        return shortcuts.take(MaxShortcuts).map { AppShortcut(it.id, it.label, id) }
    }

    internal companion object {
        const val MaxShortcuts = 5
    }
}
