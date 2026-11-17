package com.brightblu.parc.core.data.comms

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
     * instead. The stored rows (written on post) are untouched.
     */
    Dismissed,

    /** Nothing was left to act on (the notifications had already gone). */
    Unavailable,

    /** The action existed but its PendingIntent refused or threw. Nothing else was touched. */
    Failed,
}

/**
 * A UI projection of one stored message (T214), kept for the existing Messages history view. The
 * row itself lives in [CommsHistoryStore]; [removedAtMs] falls back to the recorded time while the
 * notification is still live. Never a second copy of the data.
 */
data class CommsHistoryEntry(
    val conversation: CommsConversationId,
    val title: String,
    val message: CommsMessage,
    val removedAtMs: Long,
)

/** Pure choice of which notification should receive mark-as-read. */
internal object CommsReadPlanner {

    /**
     * The newest message whose notification offers the action, or null when none does. The
     * platform's action is per notification, so any message from it is an equivalent target.
     */
    fun markAsReadTarget(messages: List<CommsMessage>): CommsMessage? =
        messages.lastOrNull { it.canMarkRead }
}
