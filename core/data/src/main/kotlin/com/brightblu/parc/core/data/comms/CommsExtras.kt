package com.brightblu.parc.core.data.comms

import com.brightblu.parc.core.data.lock.NotificationLockPolicy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canned replies for the Comms quick-reply row (T76). The list is user data, so it lives in
 * DataStore; only the four card defaults and the eight-entry ceiling are code.
 *
 * [sanitize] is the single rule shared by the settings editor, the store and the repository:
 * one line per reply (a line break would break the stored encoding), no blanks, no exact
 * duplicates, at most [Max] entries in the order given. An empty list is valid and is kept: it
 * means the user removed every canned reply, not "restore the defaults".
 */
object CommsCannedReplies {

    /** Hard ceiling from the card. */
    const val Max = 8

    /** The card defaults, in the order the chips should show them. */
    val Default: List<String> = listOf("On my way", "Call you later", "Thanks", "OK")

    /** Normalises [replies]; always returns an immutable list of at most [Max] entries. */
    fun sanitize(replies: List<String>): List<String> = replies.asSequence()
        .map { it.replace('\r', ' ').replace('\n', ' ').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(Max)
        .toList()
}

/** Outcome of [CommsExtrasRepository.markAsRead]. */
enum class MarkReadResult {

    /** The notification's own mark-as-read PendingIntent accepted the request. */
    Marked,

    /**
     * No notification in the conversation offers the action, so its notifications were cancelled
     * instead. The messages move to the history when that is enabled.
     */
    Dismissed,

    /** Nothing was left to act on (the notifications had already gone). */
    Unavailable,

    /** The action existed but its PendingIntent refused or threw. Nothing else was touched. */
    Failed,
}

/**
 * One dismissed message kept for the Comms history toggle (T76). [conversation] and [title] let a
 * later UI group a dismissed line back into its conversation; [removedAtMs] is when the
 * notification left, not when the message was sent. Immutable, never persisted.
 */
data class CommsHistoryEntry(
    val conversation: CommsConversationId,
    val title: String,
    val message: CommsMessage,
    val removedAtMs: Long,
)

/**
 * In-memory notification history (T76). The listener files a notification's messages here just
 * before it drops them from [CommsInbox], so a dismissed message stays readable until the listener
 * disconnects or the process restarts. Nothing in this class writes to disk, a database or a log.
 *
 * Bounds and gates:
 * - At most [maxMessages] messages; the oldest removal falls off the top.
 * - [setEnabled] false (the card's `Keep history` switch off, or Comms off) clears the history and
 *   refuses later recordings.
 * - [setAllowedPackages] purges messages from an app the user removed from the allow-list.
 * - A message already recorded is not recorded twice; cancelling a notification can be observed
 *   both from the repository and from the listener's removal callback.
 *
 * Threading: records arrive on a binder thread, the UI reads on main. Every mutation and the
 * published snapshot happen under one lock; the flow value is replaced only when the list changes.
 * [clock] is injected so the bounded behaviour is testable without sleeping.
 */
internal class CommsHistory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxMessages: Int = DefaultMaxMessages,
) {

    init {
        require(maxMessages >= 1) { "maxMessages must be positive" }
    }

    private val lock = Any()

    /** Keyed by message identity so a duplicate report cannot double-file a message. */
    private val entries = LinkedHashMap<String, CommsHistoryEntry>()
    private val mutable = MutableStateFlow<List<CommsHistoryEntry>>(emptyList())
    private var enabled = true
    private var allowedPackages: Set<String> = CommsPackages.Default

    /** Dismissed messages, newest removal first. Each entry carries its own message time. */
    val history: StateFlow<List<CommsHistoryEntry>> = mutable.asStateFlow()

    /**
     * Enables or disables filing. Disabling clears what is already held, because a user who turns
     * the history off does not expect the previous content to stay in memory. Returns whether the
     * published snapshot changed.
     */
    fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        this.enabled = enabled
        if (!enabled) entries.clear()
        publishLocked()
    }

    /** Keeps only apps still on the allow-list. Returns whether the snapshot changed. */
    fun setAllowedPackages(packages: Set<String>): Boolean = synchronized(lock) {
        allowedPackages = packages
        val before = entries.size
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().conversation.app !in packages) iterator.remove()
        }
        if (entries.size == before) false else publishLocked()
    }

    /**
     * Files the messages of a notification that is about to be removed. Returns true when at least
     * one message was new. Ignored while disabled or while the app is outside the allow-list.
     */
    fun record(parsed: ParsedNotification): Boolean = synchronized(lock) {
        if (!enabled || NotificationLockPolicy.blocked(parsed.app)) return false
        if (parsed.app !in allowedPackages) return false
        val now = clock()
        var changed = false
        for (message in parsed.messages) {
            val identity = identity(parsed.notificationKey, message)
            if (entries.containsKey(identity)) continue
            entries[identity] = CommsHistoryEntry(
                conversation = CommsConversationId(parsed.app, parsed.conversationKey),
                title = parsed.title,
                message = message,
                removedAtMs = now,
            )
            changed = true
        }
        if (!changed) return false
        while (entries.size > maxMessages) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        publishLocked()
        true
    }

    /** Forgets every dismissed message, e.g. when the listener disconnects. */
    fun clear(): Boolean = synchronized(lock) {
        val changed = entries.isNotEmpty()
        entries.clear()
        if (changed) publishLocked()
        changed
    }

    fun refilterLocks() = synchronized(lock) {
        entries.values.removeAll { NotificationLockPolicy.blocked(it.conversation.app) }
        publishLocked()
    }
    private fun publishLocked(): Boolean {
        entries.values.removeAll { NotificationLockPolicy.blocked(it.conversation.app) }
        val snapshot = entries.values.toList().reversed()
        val changed = snapshot != mutable.value
        if (changed) mutable.value = snapshot
        return changed
    }

    private fun identity(notificationKey: String, message: CommsMessage): String = buildString {
        append(notificationKey)
        append('\u0000')
        append(message.timeMs)
        append('\u0000')
        append(message.sender)
        append('\u0000')
        append(message.text)
    }

    companion object {
        /**
         * The process-wide history. The platform constructs the listener service itself, so the
         * service and the repository meet here, exactly like [CommsInbox.shared].
         */
        val shared: CommsHistory by lazy { CommsHistory() }

        /** The card's ceiling: the last 100 messages are readable. */
        const val DefaultMaxMessages = 100
    }
}

/** Pure choice of which notification should receive mark-as-read. */
internal object CommsReadPlanner {

    /**
     * The newest message whose notification offers the action, or null when none does. The
     * platform's action is per notification, so any message from it is an equivalent target.
     */
    fun markAsReadTarget(messages: List<CommsMessage>): CommsMessage? =
        messages.lastOrNull { it.canMarkRead }
}
