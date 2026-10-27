package com.brightblu.parc.core.data.comms

import android.content.Context
import com.brightblu.parc.core.data.ParcPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Messaging surface for the Comms hub page (T55, consumed by T56).
 *
 * [conversations] is an immutable, in-memory snapshot, newest conversation first; it is empty while
 * the notification listener is unbound or Comms is off. [allowedApps] and [enabled] mirror the
 * DataStore settings, which are the only things this layer persists: the allow-list holds package
 * names, never message content.
 *
 * Threading: reading the flows is safe anywhere. Mutations are suspend and complete their
 * PendingIntent/cancel work on an injected dispatcher. No method logs a package name, a sender or
 * any text.
 */
interface CommsRepository {

    /** Active conversations, newest first. Messages inside one are oldest first. */
    val conversations: StateFlow<List<CommsConversation>>

    /** Packages allowed to contribute. Empty means Comms shows nothing. */
    val allowedApps: StateFlow<Set<String>>

    /** Master switch. Off clears the inbox and stops accepting posts. */
    val enabled: StateFlow<Boolean>

    /** Replaces the allow-list; blanks are ignored, an empty set is kept as empty. */
    suspend fun setAllowedApps(packages: Set<String>)

    /** Turns Comms on or off; off immediately drops every held conversation. */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Sends [text] through the newest message in [conversation] that still has a free-form reply
     * action. Blank text is [ReplyResult.Failed]; no reply action is [ReplyResult.NoReplyAction].
     */
    suspend fun reply(conversation: CommsConversation, text: String): ReplyResult

    /** Cancels the one notification that carried [message]. */
    suspend fun dismiss(message: CommsMessage)

    /** Cancels every notification that contributed to [conversation]. */
    suspend fun dismiss(conversation: CommsConversation)
}

/**
 * The T76 extras on top of [CommsRepository]: canned replies, mark-as-read and the in-memory
 * history. It is a separate interface so an older fake (the debug review activity in `:app`) keeps
 * compiling against [CommsRepository] alone, while `AppGraph` receives the full type from
 * [CommsRepositories.create].
 *
 * Canned replies and the `Keep history` switch are the only persisted values; the history itself
 * never leaves memory.
 */
interface CommsExtrasRepository : CommsRepository {

    /** Canned replies in chip order, 0..[CommsCannedReplies.Max]. Empty is a valid choice. */
    val cannedReplies: StateFlow<List<String>>

    /** The card's `Keep history` switch; off also clears the entries already held. */
    val keepHistory: StateFlow<Boolean>

    /** Dismissed messages, newest removal first. Empty while the switch is off. */
    val history: StateFlow<List<CommsHistoryEntry>>

    /** Replaces the canned replies; blanks, duplicates and extras above eight are dropped. */
    suspend fun setCannedReplies(replies: List<String>)

    /** Turns the in-memory history on or off; turning it off clears what is held. */
    suspend fun setKeepHistory(enabled: Boolean)

    /** Forgets every dismissed message; the switch is unchanged. */
    suspend fun clearHistory()

    /**
     * Marks [conversation] read. The newest notification that offers the platform's
     * `SEMANTIC_ACTION_MARK_AS_READ` action is asked to do it ([MarkReadResult.Marked]); when no
     * notification has one, its notifications are cancelled instead ([MarkReadResult.Dismissed]),
     * which is the only read signal a launcher can produce and also files them in the history.
     */
    suspend fun markAsRead(conversation: CommsConversation): MarkReadResult
}

/**
 * Platform side of reply/dismiss/mark-as-read, split out so the repository is testable without
 * Android.
 */
internal interface CommsActions {
    fun reply(notificationKey: String, text: String): ReplyResult
    fun dismiss(notificationKey: String)
    fun markAsRead(notificationKey: String): MarkReadResult
}

/**
 * Production [CommsExtrasRepository]. Constructor injection supplies the inbox, prefs, action port,
 * history, dispatcher and the scope that mirrors DataStore settings into the inbox and the history.
 *
 * The initial flows are the card's defaults, so the first notification after process start is
 * parsed before the DataStore collector has run. When the collector does run it applies the stored
 * values; a stored empty allow-list therefore hides everything, as required. [historyStore]
 * defaults to a private instance so a test can build the repository without wiring the
 * process-wide one; production always passes [CommsHistory.shared].
 */
internal class CommsRepositoryImpl(
    private val inbox: CommsInbox,
    private val prefs: ParcPrefs,
    private val actions: CommsActions,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val historyStore: CommsHistory = CommsHistory(),
) : CommsExtrasRepository {

    private val allowed = MutableStateFlow(CommsPackages.Default)
    private val on = MutableStateFlow(true)
    private val canned = MutableStateFlow(CommsCannedReplies.Default)
    private val keep = MutableStateFlow(true)

    override val conversations: StateFlow<List<CommsConversation>> = inbox.conversations
    override val allowedApps: StateFlow<Set<String>> = allowed.asStateFlow()
    override val enabled: StateFlow<Boolean> = on.asStateFlow()
    override val cannedReplies: StateFlow<List<String>> = canned.asStateFlow()
    override val keepHistory: StateFlow<Boolean> = keep.asStateFlow()
    override val history: StateFlow<List<CommsHistoryEntry>> = historyStore.history

    init {
        scope.launch {
            combine(
                prefs.commsEnabled,
                prefs.commsApps,
                prefs.commsCannedReplies,
                prefs.commsKeepHistory,
            ) { enabled, packages, replies, keepHistory ->
                CommsSettingsSnapshot(enabled, packages, replies, keepHistory)
            }.collect { settings ->
                on.value = settings.enabled
                allowed.value = settings.allowedPackages
                canned.value = settings.cannedReplies
                keep.value = settings.keepHistory
                inbox.setPolicy(
                    CommsPolicy(
                        enabled = settings.enabled,
                        allowedPackages = settings.allowedPackages,
                    ),
                )
                historyStore.setEnabled(settings.enabled && settings.keepHistory)
                historyStore.setAllowedPackages(settings.allowedPackages)
            }
        }
    }

    override suspend fun setAllowedApps(packages: Set<String>) {
        val cleaned = packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        allowed.value = cleaned
        inbox.setPolicy(CommsPolicy(enabled = on.value, allowedPackages = cleaned))
        historyStore.setAllowedPackages(cleaned)
        prefs.setCommsApps(cleaned)
    }

    override suspend fun setEnabled(enabled: Boolean) {
        on.value = enabled
        inbox.setPolicy(CommsPolicy(enabled = enabled, allowedPackages = allowed.value))
        historyStore.setEnabled(enabled && keep.value)
        prefs.setCommsEnabled(enabled)
    }

    override suspend fun setCannedReplies(replies: List<String>) {
        val cleaned = CommsCannedReplies.sanitize(replies)
        canned.value = cleaned
        prefs.setCommsCannedReplies(cleaned)
    }

    override suspend fun setKeepHistory(enabled: Boolean) {
        keep.value = enabled
        historyStore.setEnabled(on.value && enabled)
        prefs.setCommsKeepHistory(enabled)
    }

    override suspend fun clearHistory() {
        historyStore.clear()
    }

    override suspend fun reply(conversation: CommsConversation, text: String): ReplyResult =
        withContext(io) {
            val body = text.trim()
            if (body.isEmpty()) return@withContext ReplyResult.Failed
            val message = conversation.messages.lastOrNull { it.canReply }
                ?: return@withContext ReplyResult.NoReplyAction
            actions.reply(message.notificationKey, body)
        }

    override suspend fun markAsRead(conversation: CommsConversation): MarkReadResult =
        withContext(io) {
            val target = CommsReadPlanner.markAsReadTarget(conversation.messages)
            if (target != null) {
                val result = actions.markAsRead(target.notificationKey)
                if (result != MarkReadResult.Unavailable) return@withContext result
            }
            dismissKeys(notificationKeys(conversation))
        }

    override suspend fun dismiss(message: CommsMessage) {
        withContext(io) {
            record(message.notificationKey)
            actions.dismiss(message.notificationKey)
        }
    }

    override suspend fun dismiss(conversation: CommsConversation) {
        withContext(io) { dismissKeys(notificationKeys(conversation)) }
    }

    /** Distinct notification keys of [conversation], newest first, exactly once each. */
    private fun notificationKeys(conversation: CommsConversation): List<String> {
        val keys = LinkedHashSet<String>()
        for (message in conversation.messages.asReversed()) keys += message.notificationKey
        return keys.toList()
    }

    /**
     * Files what is about to disappear in the history, then cancels the notifications. Used by the
     * dismiss overloads and by the mark-as-read fallback.
     */
    private fun dismissKeys(keys: Collection<String>): MarkReadResult {
        if (keys.isEmpty()) return MarkReadResult.Unavailable
        for (key in keys) record(key)
        for (key in keys) actions.dismiss(key)
        return MarkReadResult.Dismissed
    }

    private fun record(notificationKey: String) {
        inbox.entryFor(notificationKey)?.let(historyStore::record)
    }
}

/** One DataStore snapshot, so the four settings are applied in a single collector step. */
private data class CommsSettingsSnapshot(
    val enabled: Boolean,
    val allowedPackages: Set<String>,
    val cannedReplies: List<String>,
    val keepHistory: Boolean,
)

/** Builds the production Comms repository for `AppGraph` (wiring lives in `:app`, T56). */
object CommsRepositories {

    /**
     * Binds the shared inbox/action/history stores and returns the repository. The caller owns
     * [scope] and keeps it for the process lifetime, because it mirrors the DataStore settings into
     * the inbox and the history. Call once per process next to the other AppGraph services.
     */
    fun create(
        context: Context,
        prefs: ParcPrefs,
        scope: CoroutineScope,
    ): CommsExtrasRepository {
        val actions = AndroidCommsActions.shared
        actions.bindContext(context.applicationContext)
        return CommsRepositoryImpl(
            inbox = CommsInbox.shared,
            prefs = prefs,
            actions = actions,
            io = Dispatchers.IO,
            scope = scope,
            historyStore = CommsHistory.shared,
        )
    }
}
