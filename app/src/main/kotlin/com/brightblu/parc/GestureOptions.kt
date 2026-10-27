package com.brightblu.parc

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/**
 * T69: the action a swipe up or a hub long-press performs. Framework-free and pure; the string
 * form is the persistence format, so the store, the settings rows and the tests share one map.
 *
 * `Edit mode` is the hub long-press default and keeps the pre-T69 behaviour. OPUS8 (T133): the
 * swipe-up default is `All apps` (the owner's answer 7); a stored choice, including `None`, is kept. The notification-shade action named by the card is not offered: the platform
 * exposes no public `StatusBarManager` API, and hidden-API reflection is not acceptable.
 */
sealed interface GestureAction {
    data object None : GestureAction
    data object AllApps : GestureAction
    data object Search : GestureAction
    data object LastApp : GestureAction
    data object EditMode : GestureAction
    data class HubPage(val page: String) : GestureAction
    data class OpenApp(val appId: AppId) : GestureAction

    fun encode(): String = when (this) {
        None -> "none"
        AllApps -> "all_apps"
        Search -> "search"
        LastApp -> "last_app"
        EditMode -> "edit_mode"
        is HubPage -> "page:$page"
        is OpenApp -> "app:${appId.value}"
    }

    companion object {
        /** Unknown or malformed text returns [fallback]; the default therefore survives an upgrade. */
        fun decode(raw: String?, fallback: GestureAction): GestureAction = when {
            raw.isNullOrEmpty() -> fallback
            raw == "none" -> None
            raw == "all_apps" -> AllApps
            raw == "search" -> Search
            raw == "last_app" -> LastApp
            raw == "edit_mode" -> EditMode
            raw.startsWith("page:") -> raw.removePrefix("page:").takeIf { it.isNotEmpty() }?.let(::HubPage)
                ?: fallback
            raw.startsWith("app:") -> raw.removePrefix("app:").takeIf { it.isNotEmpty() }
                ?.let { OpenApp(AppId(it)) } ?: fallback
            else -> fallback
        }
    }
}

/** The two configurable gestures. The key is the stable persistence id. */
enum class GestureSlot(val key: String) {
    SwipeUp("swipe_up"),
    HubLongPress("hub_long_press"),
}

data class GestureConfig(
    val swipeUp: GestureAction = GestureAction.AllApps,
    val hubLongPress: GestureAction = GestureAction.EditMode,
) {
    companion object {
        /**
         * OPUS8 (T133): the stored texts to a config. This is the migration of the swipe-up default:
         * only an unset (or unreadable) value takes the new default `All apps`; anyone who ever
         * chose an action, `None` included, has it stored as text and keeps it. A restored backup
         * goes through the same path, so an old backup with `none` stays `None`.
         */
        fun decode(swipeUpRaw: String?, hubLongPressRaw: String?): GestureConfig {
            val defaults = GestureConfig()
            return GestureConfig(
                swipeUp = GestureAction.decode(swipeUpRaw, defaults.swipeUp),
                hubLongPress = GestureAction.decode(hubLongPressRaw, defaults.hubLongPress),
            )
        }
    }

    fun actionFor(slot: GestureSlot): GestureAction = when (slot) {
        GestureSlot.SwipeUp -> swipeUp
        GestureSlot.HubLongPress -> hubLongPress
    }

    fun with(slot: GestureSlot, action: GestureAction): GestureConfig = when (slot) {
        GestureSlot.SwipeUp -> copy(swipeUp = action)
        GestureSlot.HubLongPress -> copy(hubLongPress = action)
    }
}

/** What a stored action turns into at gesture time. */
internal sealed interface GestureTarget {
    data object Nothing : GestureTarget
    data object AllApps : GestureTarget
    data object Search : GestureTarget
    data object LastApp : GestureTarget
    data object EditMode : GestureTarget
    data class HubPage(val page: String) : GestureTarget
    data class OpenApp(val appId: AppId) : GestureTarget
}

/**
 * Maps a stored action to something the session can run. A chosen app that is no longer installed
 * resolves to nothing; a chosen hub page that is hidden (or no longer exists) falls back to the
 * first visible page, matching [restoredHubPage]. No visible page at all resolves to nothing.
 */
internal fun resolveGesture(
    action: GestureAction,
    pages: List<String>,
    installed: (AppId) -> Boolean,
): GestureTarget = when (action) {
    GestureAction.None -> GestureTarget.Nothing
    GestureAction.AllApps -> GestureTarget.AllApps
    GestureAction.Search -> GestureTarget.Search
    GestureAction.LastApp -> GestureTarget.LastApp
    GestureAction.EditMode -> GestureTarget.EditMode
    is GestureAction.HubPage -> {
        val page = action.page.takeIf { it in pages } ?: pages.firstOrNull()
        if (page == null) GestureTarget.Nothing else GestureTarget.HubPage(page)
    }
    is GestureAction.OpenApp -> if (installed(action.appId)) GestureTarget.OpenApp(action.appId)
        else GestureTarget.Nothing
}

/** Swipe-up is armed only when it has an action and starts on empty space, never on the hub panel. */
internal object SwipeUpGate {
    fun armed(action: GestureAction, blocked: Boolean, onControl: Boolean, fromHubPanel: Boolean): Boolean =
        action != GestureAction.None && !blocked && !onControl && !fromHubPanel
}

/** Human label for a stored action, used by the root settings rows. */
internal fun gestureActionLabel(action: GestureAction, appLabel: (AppId) -> String? = { null }): String =
    when (action) {
        GestureAction.None -> "None"
        GestureAction.AllApps -> "All apps"
        GestureAction.Search -> "Search"
        GestureAction.LastApp -> "Last app"
        GestureAction.EditMode -> "Edit mode"
        is GestureAction.HubPage -> "Stats · ${hubPageLabel(action.page)}"
        is GestureAction.OpenApp -> appLabel(action.appId)?.let { "App · $it" } ?: "App · not installed"
    }

internal fun hubPageLabel(page: String): String =
    page.replaceFirstChar { it.uppercase() }

/** One row of the gesture settings list. Pure: the Compose host only renders and dispatches. */
internal sealed interface GestureRow {
    data class Pick(val action: GestureAction, val label: String, val checked: Boolean) : GestureRow
    data class Picker(
        val label: String,
        val detail: String,
        val checked: Boolean,
        val picker: GesturePicker,
    ) : GestureRow
}

internal enum class GesturePicker { HubPage, App }

/** The action rows for one gesture, in the order the card lists them. */
internal fun gestureRows(
    slot: GestureSlot,
    current: GestureAction,
    appLabel: (AppId) -> String? = { null },
): List<GestureRow> = buildList {
    add(GestureRow.Pick(GestureAction.None, "None", current == GestureAction.None))
    add(GestureRow.Pick(GestureAction.AllApps, "All apps view", current == GestureAction.AllApps))
    add(GestureRow.Pick(GestureAction.Search, "Search", current == GestureAction.Search))
    add(GestureRow.Pick(GestureAction.LastApp, "Last app", current == GestureAction.LastApp))
    // The hub default must stay reachable after another action is chosen.
    if (slot == GestureSlot.HubLongPress) {
        add(GestureRow.Pick(GestureAction.EditMode, "Edit mode", current == GestureAction.EditMode))
    }
    val page = (current as? GestureAction.HubPage)?.page
    add(GestureRow.Picker(
        label = "Stats hub page",
        detail = page?.let(::hubPageLabel) ?: "Choose a page",
        checked = page != null,
        picker = GesturePicker.HubPage,
    ))
    val app = (current as? GestureAction.OpenApp)?.appId
    add(GestureRow.Picker(
        label = "Open an app",
        detail = app?.let(appLabel) ?: "Choose an app",
        checked = app != null,
        picker = GesturePicker.App,
    ))
}

/** The page picker: one row per currently visible hub page. */
internal fun gesturePageRows(pages: List<String>, current: GestureAction): List<GestureRow.Pick> =
    pages.map { page ->
        GestureRow.Pick(GestureAction.HubPage(page), hubPageLabel(page),
            current == GestureAction.HubPage(page))
    }

/** The app picker: one row per launchable, non-hidden app, ordered by label then id. */
internal fun gestureAppRows(entries: List<AppEntry>, current: GestureAction): List<GestureRow.Pick> =
    entries.sortedWith(compareBy({ it.label.lowercase() }, { it.id.value })).map { entry ->
        GestureRow.Pick(GestureAction.OpenApp(entry.id), entry.label,
            current == GestureAction.OpenApp(entry.id))
    }
