package com.brightblu.parc.core.data.comms

import com.brightblu.parc.core.data.lock.NotificationLockPolicy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One post outcome: accepted, or dropped with a package-only reason. */
internal data class CommsPostOutcome(val accepted: Boolean, val drop: CommsDrop?)

/**
 * In-memory aggregation behind [CommsRepository] (T55). The listener feeds raw notifications in;
 * the inbox keeps only parsed conversations, publishes an immutable snapshot, and forgets
 * everything when the listener disconnects or Comms is switched off.
 *
 * Nothing here touches disk, a database, a file or a log. Persistence is
 * [CommsHistoryStore], not this class: the listener writes accepted posts there separately. The
 * only state here is a map of the active notifications' parsed messages, bounded by
 * [maxNotifications] and by the per-conversation message cap, so a misbehaving app cannot grow
 * memory without bound.
 *
 * Threading: listener callbacks arrive on a binder thread and the UI reads on main. Every mutation
 * and the published value happen under one lock, so a collector never sees a half-updated
 * conversation. The flow value is replaced only when the projected list actually changes.
 */
internal class CommsInbox(
    private val parser: CommsParser = CommsParser(),
    private val maxNotifications: Int = DefaultMaxNotifications,
    private val maxMessagesPerConversation: Int = DefaultMaxMessagesPerConversation,
) {

    init {
        require(maxNotifications >= 1) { "maxNotifications must be positive" }
        require(maxMessagesPerConversation >= 1) { "maxMessagesPerConversation must be positive" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, ParsedNotification>()
    private var policy = CommsPolicy()
    private val mutable = MutableStateFlow<List<CommsConversation>>(emptyList())

    /** Newest conversation first; messages inside each conversation are oldest first. */
    val conversations: StateFlow<List<CommsConversation>> = mutable.asStateFlow()

    /** True when [packageName] is allowed to contribute right now. Cheap enough for a binder thread. */
    fun shouldParse(packageName: String): Boolean = synchronized(lock) {
        !NotificationLockPolicy.blocked(packageName) && policy.enabled && packageName in policy.allowedPackages
    }

    /**
     * Why [packageName] would be skipped before the extractor reads it, or null when it is allowed
     * (or Comms is off, which is a page state rather than a dropped notification). Package only.
     */
    fun policyDropReason(packageName: String): CommsDropReason? = synchronized(lock) {
        when {
            !policy.enabled -> null
            packageName !in policy.allowedPackages -> CommsDropReason.NotAllowed
            else -> null
        }
    }

    /**
     * Applies a new allow-list/switch. Disabling clears the inbox; removing a package drops its
     * conversations and keeps the rest. Returns whether the published snapshot changed.
     */
    fun setPolicy(next: CommsPolicy): Boolean = synchronized(lock) {
        policy = next
        if (!next.enabled) {
            entries.clear()
        } else {
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().app !in next.allowedPackages) iterator.remove()
            }
        }
        publishLocked()
    }

    /**
     * Adds or replaces one notification's contribution. Returns true when the notification became
     * a conversation; false when the card's filters dropped it (an earlier contribution for the
     * same key is removed, because a notification update can stop being a message).
     */
    fun post(raw: RawNotification): Boolean = postWithOutcome(raw).accepted

    /**
     * The [post] path with the package-only drop reason the listener needs for the Why sheet.
     * A locked package or Comms being off is ignored, not reported as a filter drop.
     */
    internal fun postWithOutcome(raw: RawNotification): CommsPostOutcome = synchronized(lock) {
        val result = if (NotificationLockPolicy.blocked(raw.packageName)) null else parser.evaluate(raw, policy)
        val parsed = (result as? CommsParseResult.Kept)?.notification
        val changed = if (parsed == null) {
            entries.remove(raw.key) != null
        } else {
            entries.remove(parsed.notificationKey)
            entries[parsed.notificationKey] = parsed
            trimNotificationsLocked()
            true
        }
        if (changed) publishLocked()
        when (result) {
            null, CommsParseResult.Ignored -> CommsPostOutcome(accepted = false, drop = null)
            is CommsParseResult.Dropped -> CommsPostOutcome(
                accepted = false,
                drop = CommsDrop(raw.packageName, result.reason),
            )
            is CommsParseResult.Kept -> CommsPostOutcome(
                accepted = !NotificationLockPolicy.blocked(result.notification.app),
                drop = null,
            )
        }
    }

    /**
     * The parsed notification behind [notificationKey], or null. The listener reads it on post or
     * connect so [CommsHistoryStore] can write the accepted messages (T214).
     */
    fun entryFor(notificationKey: String): ParsedNotification? = synchronized(lock) {
        entries[notificationKey]?.takeUnless { NotificationLockPolicy.blocked(it.app) }
    }

    /** Drops one notification by key. Unknown keys are a no-op. */
    fun remove(notificationKey: String): Boolean = synchronized(lock) {
        val changed = entries.remove(notificationKey) != null
        if (changed) publishLocked()
        changed
    }

    /**
     * Replaces the whole inbox from the listener's `activeNotifications`. Returns the accepted
     * notification keys so the Android action port can keep exactly those notifications.
     */
    fun rebuild(raws: List<RawNotification>): Set<String> = synchronized(lock) {
        entries.clear()
        for (raw in raws) {
            val parsed = (if (NotificationLockPolicy.blocked(raw.packageName)) null else parser.parse(raw, policy)) ?: continue
            entries.remove(parsed.notificationKey)
            entries[parsed.notificationKey] = parsed
        }
        trimNotificationsLocked()
        publishLocked()
        LinkedHashSet(entries.keys)
    }

    /** Forgets every conversation, e.g. when the listener disconnects. */
    fun clear(): Boolean = synchronized(lock) {
        val changed = entries.isNotEmpty()
        entries.clear()
        if (changed) publishLocked()
        changed
    }

    private fun trimNotificationsLocked() {
        while (entries.size > maxNotifications) {
            val oldest = entries.keys.firstOrNull() ?: return
            entries.remove(oldest)
        }
    }

    fun refilterLocks() = synchronized(lock) {
        entries.values.removeAll { NotificationLockPolicy.blocked(it.app) }
        publishLocked()
    }
    private fun publishLocked(): Boolean {
        entries.values.removeAll { NotificationLockPolicy.blocked(it.app) }
        val groups = LinkedHashMap<CommsConversationId, Group>()
        for (entry in entries.values) {
            val id = CommsConversationId(entry.app, entry.conversationKey)
            val group = groups.getOrPut(id) { Group(entry.title, entry.updatedAtMs) }
            if (entry.updatedAtMs >= group.updatedAtMs) {
                group.title = entry.title
                group.updatedAtMs = entry.updatedAtMs
            }
            group.messages += entry.messages
        }

        val snapshot = groups.map { (id, group) ->
            val ordered = group.messages.sortedWith(compareBy { it.timeMs })
            val messages = if (ordered.size > maxMessagesPerConversation) {
                ordered.takeLast(maxMessagesPerConversation)
            } else {
                ordered
            }
            CommsConversation(
                id = id,
                title = group.title,
                messages = messages,
                canReply = messages.any { it.canReply },
                updatedAtMs = group.updatedAtMs,
                canMarkRead = messages.any { it.canMarkRead },
            )
        }.sortedWith(
            compareByDescending<CommsConversation> { it.updatedAtMs }
                .thenBy { it.id.app }
                .thenBy { it.id.key },
        )

        val changed = snapshot != mutable.value
        if (changed) mutable.value = snapshot
        return changed
    }

    private class Group(var title: String, var updatedAtMs: Long) {
        val messages = ArrayList<CommsMessage>()
    }

    companion object {
        /**
         * The process-wide inbox. The platform constructs the listener service itself, so the
         * service and the repository meet here; nothing else is shared.
         */
        val shared: CommsInbox by lazy { CommsInbox() }

        /** Active notifications kept. A busy day is tens; this is the memory ceiling. */
        const val DefaultMaxNotifications = 100

        /** Message lines kept per conversation; older lines fall off the top. */
        const val DefaultMaxMessagesPerConversation = 60
    }
}
