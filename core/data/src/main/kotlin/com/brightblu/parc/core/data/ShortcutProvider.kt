package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppShortcut

private const val CalendarShortcutPrefix = "launcher_shortcuts_shortcut_"
private val wordId = Regex("[A-Za-z]+(?:[_-][A-Za-z]+)+")

/** Prefer the publisher's localized text. Calendar-style ids can also describe an action. */
internal fun shortcutLabel(id: String, shortLabel: CharSequence?, longLabel: CharSequence?): String {
    for (candidate in listOf(shortLabel, longLabel)) {
        val text = candidate?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && !text.startsWith(CalendarShortcutPrefix) &&
            (text != id || text.any(Char::isWhitespace))) return text
    }
    val action = id.removePrefix(CalendarShortcutPrefix)
    if (!wordId.matches(action)) return "Shortcut"
    return action.lowercase().replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
}

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
