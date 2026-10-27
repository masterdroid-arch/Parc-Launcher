package com.brightblu.parc

import com.brightblu.parc.core.data.notifications.AndroidPeekActions
import com.brightblu.parc.core.data.notifications.NotificationPeekStore
import com.brightblu.parc.core.data.notifications.PeekLine
import com.brightblu.parc.core.data.notifications.PeekRules

/**
 * T97: what the long-press menus need from the notification listener. The session depends on this
 * interface only, so the menu composition and the dismiss flow are tested with fakes. Every method
 * must be safe to call without notification access: it then simply has nothing to show.
 */
interface MenuNotificationSource {
    /** Up to three rows for [packageName], newest first, with the privacy rules already applied. */
    fun lines(packageName: String): List<PeekLine>

    /** Notification count per package (filtered like [lines]), for the folder menu. */
    fun counts(): Map<String, Int>

    /** Fires the notification's content intent. False when it is gone. */
    fun open(key: String): Boolean

    fun dismiss(key: String)

    /** Dismisses every listed notification of [packageName] (the same ones [lines] could show). */
    fun dismissAll(packageName: String)
}

/** The live source: the in-memory peek store plus the listener's cancel and open port. */
internal class ListenerMenuNotifications(
    private val prefs: ParcUiPrefs,
    private val store: NotificationPeekStore = NotificationPeekStore.shared,
    private val actions: AndroidPeekActions = AndroidPeekActions.shared,
    /** T98: packages hidden by a locked folder; they have no menu lines and no folder counts. */
    private val blocked: () -> Set<String> = { emptySet() },
) : MenuNotificationSource {
    override fun lines(packageName: String): List<PeekLine> {
        if (!prefs.menuNotifications() || packageName in blocked()) return emptyList()
        val showText = prefs.menuNotificationText()
        return PeekRules.forApp(store.snapshot(), packageName).map { PeekRules.line(it, showText) }
    }

    override fun counts(): Map<String, Int> =
        if (prefs.menuNotifications()) {
            com.brightblu.parc.core.data.lock.LockedApps.visibleCounts(PeekRules.counts(store.snapshot()), blocked())
        } else emptyMap()

    override fun open(key: String): Boolean = actions.open(key)

    override fun dismiss(key: String) = actions.cancel(key)

    override fun dismissAll(packageName: String) {
        val keys = store.snapshot().filter { it.packageName == packageName && PeekRules.accepts(it) }.map { it.key }
        actions.cancelAll(keys)
    }
}

/** Process-level switch for the in-memory collection behind `Notifications in menus`. */
internal object MenuNotifications {
    fun applyEnabled(enabled: Boolean) {
        val store = NotificationPeekStore.shared
        val wasOn = store.collecting
        store.collecting = enabled
        if (enabled && !wasOn) AndroidPeekActions.shared.refresh()
    }
}
