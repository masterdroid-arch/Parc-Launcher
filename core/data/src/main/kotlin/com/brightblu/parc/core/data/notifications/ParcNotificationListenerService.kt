package com.brightblu.parc.core.data.notifications

import com.brightblu.parc.core.data.lock.NotificationLockPolicy
import android.app.Notification
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.brightblu.parc.core.data.comms.AndroidCommsActions
import com.brightblu.parc.core.data.comms.CommsHistoryStore
import com.brightblu.parc.core.data.comms.CommsImageLoader
import com.brightblu.parc.core.data.comms.CommsInbox
import com.brightblu.parc.core.data.comms.CommsNotificationExtractor
import com.brightblu.parc.core.data.comms.RawNotification

/**
 * Parc's notification listener (PROJECT.md §6.3, T15; extended by T55 for the Comms page).
 *
 * Two consumers share this one service:
 * - [NotificationUnreadStore.shared] keeps per-package counts for the ring pulse. It never reads
 *   content.
 * - [CommsInbox.shared] keeps the conversations the Comms page shows, including text and a 1-bit
 *   thumbnail, strictly in memory. The Android action port keeps the matching notifications only to
 *   send a reply, mark one read or cancel one, and is cleared at disconnect.
 * - [CommsHistoryStore.shared] persists accepted messages from WhatsApp, Telegram and Instagram in
 *   its own database (T214), text and link spans only, never images and never a backup. It survives
 *   disconnect and process death; disconnect only marks rows stale.
 *
 * The service is bound by the system after the user grants Notification access under Special access
 * (the settings link is `feature/settings`); Parc never asks for a permission and declares none.
 *
 * Behaviour pinned by the cards:
 * - Connect: rebuild from `activeNotifications`, because anything posted or removed while unbound
 *   was never delivered; mark stored rows stale first, then mark the ones still active live again.
 * - Post/remove: forward the opaque key to the counts and the parsed notification to Comms; the
 *   accepted post is written to history immediately. Removal marks the row stale and keeps it.
 * - Disconnect: forget the counts and the live conversations (they cannot be kept correct while
 *   unbound), mark history rows stale without deleting them, and ask the system to rebind,
 *   rate-limited by [RebindGate].
 *
 * T55 does not add a second service. This is still the only listener Parc declares.
 */
class ParcNotificationListenerService : NotificationListenerService() {

    private val gate = RebindGate()

    /** The shared store; the platform constructs this service, so it is not injected. */
    private val store: NotificationUnreadStore get() = NotificationUnreadStore.shared

    /** T55's in-memory conversations, process-wide for the same reason as [store]. */
    private val commsInbox: CommsInbox get() = CommsInbox.shared

    /** T214's persistent message history, process-wide for the same reason. */
    private val commsHistory: CommsHistoryStore
        get() = CommsHistoryStore.shared(applicationContext)

    /** The reply/cancel port; it attaches to this service on connect. */
    private val commsActions: AndroidCommsActions get() = AndroidCommsActions.shared

    private val commsExtractor by lazy {
        CommsNotificationExtractor(CommsImageLoader(contentResolver))
    }

    /**
     * OPUS6 (audit F16): listener callbacks arrive on the main thread, and a message notification can
     * carry a photo (up to 2 MB) that is read and decoded while extracting it. All callback work now
     * runs on this one background thread, in arrival order, so Home never stutters while a busy chat
     * posts photos. The stores it updates are thread-safe.
     */
    private val worker by lazy { android.os.HandlerThread("parc-notifications").apply { start() } }
    private val work by lazy { android.os.Handler(worker.looper) }

    override fun onListenerConnected() {
        NotificationListenerStatus.onConnected()
        // OPUS6 (audit F10): when Home loads the lock policy after this connect (after a reboot or a
        // kill the system binds the listener first), rebuild everything then, instead of staying
        // empty until each app posts again.
        NotificationLockPolicy.onReady = { work.post { connectedWork() } }
        work.post { connectedWork() }
    }

    private fun connectedWork() {
        try {
        // T214: a fresh connect reconciles the live hint. No row is deleted; active notifications
        // are re-marked live as they are rebuilt below.
        historyUpdate { commsHistory.markAllNotLive() }
        val active = activeNotificationsSafely()
        store.rebuilt(unreadKeys(active))
        commsActions.attach(this)
        peekActions.attach(this)
        rebuildPeek(active)

        val raws = ArrayList<RawNotification>(active?.size ?: 0)
        for (sbn in active.orEmpty()) {
            if (!NotificationLockPolicy.mayRead(sbn.packageName) || !commsInbox.shouldParse(sbn.packageName)) continue
            runCatching { commsExtractor.extract(sbn) }.getOrNull()?.let(raws::add)
        }
        val accepted = commsInbox.rebuild(raws)
        recordAccepted(raws, accepted)
        val kept = LinkedHashMap<String, Notification>()
        for (sbn in active.orEmpty()) {
            if (sbn.key !in accepted) continue
            val notification = sbn.notification ?: continue
            kept[sbn.key] = notification
        }
        commsActions.replaceAll(kept)

        } catch (_: Exception) { /* Drop malformed or revoked data; never log content. */ }
    }

    /**
     * T214: write the notifications the inbox accepted on connect. The next post or connect retries
     * the same identity if a write fails, so a history failure never drops the live card.
     */
    private fun recordAccepted(raws: List<RawNotification>, accepted: Set<String>) {
        for (raw in raws) {
            if (raw.key !in accepted) continue
            val parsed = commsInbox.entryFor(raw.key) ?: continue
            try {
                commsHistory.record(parsed)
            } catch (error: Exception) {
                // Package and exception class only: never a title, sender, key or message body.
                Log.e(Tag, "history write failed for ${parsed.app}: ${error.javaClass.simpleName}")
            }
        }
    }

    /** T214 state updates are best-effort; history must never break the live listener. */
    private inline fun historyUpdate(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.e(Tag, "history state update failed: ${error.javaClass.simpleName}")
        }
    }

    override fun onListenerDisconnected() {
        NotificationLockPolicy.onReady = null
        NotificationListenerStatus.onDisconnected()
        work.post { disconnectedWork() }
    }

    private fun disconnectedWork() {
        try {
        store.cleared()
        commsInbox.clear()
        // T214: keep the stored history; a disconnect only means the rows are no longer live.
        historyUpdate { commsHistory.markAllNotLive() }
        commsActions.detach(this)
        clearPeek()
        requestRebindSafely()

        } catch (_: Exception) { /* Drop malformed or revoked data; never log content. */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        work.post { postedWork(sbn) }
    }

    private fun postedWork(sbn: StatusBarNotification?) {
        try {
        val notification = sbn ?: return
        if (!NotificationLockPolicy.mayRead(notification.packageName)) return
        store.posted(
            packageName = notification.packageName,
            key = notification.key,
            unread = countsAsUnread(notification),
        )
        peekPosted(notification)

        if (!commsInbox.shouldParse(notification.packageName)) {
            // Package-only diagnostics: never keep text, sender or title for a dropped post.
            commsInbox.policyDropReason(notification.packageName)?.let { reason ->
                NotificationListenerStatus.recordDrop(notification.packageName, reason)
            }
            commsInbox.remove(notification.key)
            commsActions.forget(notification.key)
            return
        }
        val raw = runCatching { commsExtractor.extract(notification) }.getOrNull()
        val outcome = raw?.let { commsInbox.postWithOutcome(it) }
        val accepted = outcome?.accepted == true
        outcome?.drop?.let { drop ->
            NotificationListenerStatus.recordDrop(drop.packageName, drop.reason)
        }
        // T214: write on post, before any removal, so a message survives a kill or disconnect.
        if (accepted) {
            commsInbox.entryFor(notification.key)?.let { parsed ->
                try {
                    commsHistory.record(parsed)
                } catch (error: Exception) {
                    // Package and exception class only; the live card must not be dropped.
                    Log.e(Tag, "history write failed for ${parsed.app}: ${error.javaClass.simpleName}")
                }
            }
        }
        val body = notification.notification
        if (accepted && body != null) {
            commsActions.remember(notification.key, body)
        } else {
            commsActions.forget(notification.key)
        }

        } catch (_: Exception) { /* Drop malformed or revoked data; never log content. */ }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        work.post { removedWork(sbn) }
    }

    private fun removedWork(sbn: StatusBarNotification?) {
        try {
        val key = sbn?.key ?: return
        store.removed(key)
        peekStore.remove(key)
        peekActions.forget(key)
        // T214: keep the row; removal only clears the live hint and records when it happened.
        historyUpdate { commsHistory.markRemoved(key) }
        commsInbox.remove(key)
        commsActions.forget(key)

        } catch (_: Exception) { /* Drop malformed or revoked data; never log content. */ }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        // The platform's API 26+ entry point; delegating keeps one removal rule for every version.
        onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        NotificationLockPolicy.onReady = null
        NotificationListenerStatus.clear()
        work.post {
            try {
                // Belt and braces: some OEM builds destroy the service without a disconnect callback.
                commsInbox.clear()
                // T214: never delete the store here. Only mark the rows stale.
                historyUpdate { commsHistory.markAllNotLive() }
                commsActions.detach(this)
                clearPeek()
            } catch (_: Exception) { /* Drop malformed or revoked data; never log content. */ }
        }
        worker.quitSafely()
        try { super.onDestroy() } catch (_: Exception) { }
    }

    /** T97: the in-memory source of the long-press menu's notification rows. */
    private val peekStore: NotificationPeekStore get() = NotificationPeekStore.shared
    private val peekActions: AndroidPeekActions get() = AndroidPeekActions.shared

    private fun clearPeek() {
        peekStore.clear()
        peekActions.detach(this)
    }

    private fun rebuildPeek(active: Array<StatusBarNotification>?) {
        try {
            val entries = ArrayList<PeekEntry>()
            peekActions.clearIntents()
            if (peekStore.collecting) {
                for (sbn in active.orEmpty()) {
                    val entry = peekEntryOf(sbn) ?: continue
                    entries += entry
                    rememberIntent(sbn)
                }
            }
            peekStore.replaceAll(entries)
        } catch (e: Exception) {
            peekStore.clear()
        }
    }

    /** Re-reads the active notifications, for when the user turns the menu section on. */
    fun refreshPeek() { work.post { rebuildPeek(activeNotificationsSafely()) } }

    private fun peekPosted(sbn: StatusBarNotification) {
        try {
            if (!peekStore.collecting) return
            val entry = peekEntryOf(sbn)
            if (entry == null) {
                peekStore.remove(sbn.key)
                peekActions.forget(sbn.key)
                return
            }
            peekStore.post(entry)
            rememberIntent(sbn)
        } catch (e: Exception) {
            // A malformed notification must never take the process down; the row just is not shown.
        }
    }

    private fun rememberIntent(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        peekActions.remember(sbn.key, n.contentIntent, n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    /** Null when the notification cannot be read; the rules in [PeekRules] decide the rest. */
    private fun peekEntryOf(sbn: StatusBarNotification): PeekEntry? { return try {
        if (!NotificationLockPolicy.mayRead(sbn.packageName)) return null
        val n = sbn.notification
        val extras = n?.extras
        if (n == null || extras == null) null else {
            val title = PeekRules.oneLine(extras.getCharSequence(Notification.EXTRA_TITLE))
            val text = PeekRules.oneLine(
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            )
            PeekEntry(
                key = sbn.key, packageName = sbn.packageName, title = title, text = text,
                postTime = sbn.postTime, visibility = peekVisibility(sbn),
                ongoing = sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )
        }
    } catch (e: Exception) {
        null
    }

    }

    /** The user's per-channel lock-screen choice wins over what the app declared. */
    private fun peekVisibility(sbn: StatusBarNotification): PeekVisibility {
        var value = sbn.notification?.visibility ?: Notification.VISIBILITY_PRIVATE
        try {
            val ranking = Ranking()
            if (currentRanking.getRanking(sbn.key, ranking) &&
                ranking.lockscreenVisibilityOverride != Ranking.VISIBILITY_NO_OVERRIDE
            ) {
                value = ranking.lockscreenVisibilityOverride
            }
        } catch (e: Exception) {
            // Keep the app's own declaration.
        }
        return PeekVisibility.fromPlatform(value)
    }

    /**
     * Unread means "a content notification the user has not dealt with". Ongoing notifications
     * (music, downloads, calls in progress) have no unread state and group summaries would double
     * count their children, so both are excluded. Nothing else about the notification is read.
     */
    private fun countsAsUnread(sbn: StatusBarNotification): Boolean {
        if (sbn.isOngoing) return false
        val flags = sbn.notification?.flags ?: return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private fun unreadKeys(active: Array<StatusBarNotification>?): List<UnreadKey> {
        val notifications = active ?: return emptyList()
        val keys = ArrayList<UnreadKey>(notifications.size)
        for (sbn in notifications) {
            if (!countsAsUnread(sbn)) continue
            keys += UnreadKey(sbn.packageName, sbn.key)
        }
        return keys
    }

    /**
     * `activeNotifications` throws when access was revoked between the callback and the read; a
     * revoked listener simply has nothing to count.
     */
    private fun activeNotificationsSafely(): Array<StatusBarNotification>? = try {
        activeNotifications
    } catch (e: SecurityException) {
        null
    }

    private fun requestRebindSafely() {
        if (!gate.shouldRequest(SystemClock.elapsedRealtime())) return
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, ParcNotificationListenerService::class.java),
            )
        } catch (e: RuntimeException) {
            // The component may have been disabled; the user re-enables it in Settings. No content
            // is logged, and there is nothing else Parc may do about it.
            Log.w(Tag, "notification listener rebind refused")
        }
    }

    private companion object {
        const val Tag = "ParcNotifications"
    }
}
