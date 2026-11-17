package com.brightblu.parc.core.data.comms

import android.content.Context
import com.brightblu.parc.core.data.ParcPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Messaging surface for the Comms hub page (T55, consumed by T56).
 *
 * [conversations] is an immutable, in-memory snapshot, newest conversation first; it is empty while
 * the notification listener is unbound or Comms is off. [allowedApps] and [enabled] mirror the
 * DataStore settings. Notification content is persisted separately by [CommsHistoryStore]; this
 * layer never writes message text itself.
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
 * The T76 extras on top of [CommsRepository]: canned replies, mark-as-read and the stored history.
 * It is a separate interface so an older fake (the debug review activity in `:app`) keeps
 * compiling against [CommsRepository] alone, while `AppGraph` receives the full type from
 * [CommsRepositories.create].
 *
 * Canned replies and the recording switch are persisted settings. The history itself is persisted
 * by [CommsHistoryStore] in its own database and never travels in a backup.
 */
interface CommsExtrasRepository : CommsRepository {

    /** Canned replies in chip order, 0..[CommsCannedReplies.Max]. Empty is a valid choice. */
    val cannedReplies: StateFlow<List<String>>

    /**
     * The recording switch, stored under the existing `comms_keep_history` key. Turning it off
     * pauses new writes and keeps every stored row.
     */
    val keepHistory: StateFlow<Boolean>

    /** Stored rows, newest message first. The source of truth for the History view. */
    val storedMessages: StateFlow<List<CommsMessageEntity>>

    /** A UI projection of [storedMessages] for the existing T77 history view. */
    val history: StateFlow<List<CommsHistoryEntry>>

    /** Replaces the canned replies; blanks, duplicates and extras above eight are dropped. */
    suspend fun setCannedReplies(replies: List<String>)

    /** Pauses or resumes recording; stored rows are never cleared by this call. */
    suspend fun setKeepHistory(enabled: Boolean)

    /** Deletes every stored message; only an explicit user action may call this. */
    suspend fun clearHistory()

    /** Deletes one stored message by row id (T215 long-press Delete). */
    suspend fun deleteHistoryMessage(id: Long)

    /** Deletes one stored conversation by app and conversation key (T215 long-press Delete). */
    suspend fun deleteHistoryConversation(app: String, conversationKey: String)

    /**
     * Marks [conversation] read. The newest notification that offers the platform's
     * `SEMANTIC_ACTION_MARK_AS_READ` action is asked to do it ([MarkReadResult.Marked]); when no
     * notification has one, its notifications are cancelled instead ([MarkReadResult.Dismissed]),
     * which is the only read signal a launcher can produce. Stored rows are written on post and
     * are not changed by either path.
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
 * the process-wide history store, dispatcher and the scope that mirrors DataStore settings into the
 * inbox and the store. Production always passes [CommsHistoryStore.shared]; tests pass a store over
 * a fake DAO.
 *
 * The initial flows are the card's defaults, so the first notification after process start is
 * parsed before the DataStore collector has run. When the collector does run it applies the stored
 * values; a stored empty allow-list therefore hides everything, as required.
 */
internal class CommsRepositoryImpl(
    private val inbox: CommsInbox,
    private val prefs: ParcPrefs,
    private val actions: CommsActions,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val historyStore: CommsHistoryStore,
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
    override val storedMessages: StateFlow<List<CommsMessageEntity>> =
        historyStore.messages.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val history: StateFlow<List<CommsHistoryEntry>> = storedMessages
        .map { rows -> rows.map { it.toHistoryEntry() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

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
                // Off/recording off pauses writes only; the stored rows stay on disk (T214).
                historyStore.setRecording(settings.enabled && settings.keepHistory)
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
        // Removing an app pauses its recording and keeps the rows already stored (T214).
        historyStore.setAllowedPackages(cleaned)
        prefs.setCommsApps(cleaned)
    }

    override suspend fun setEnabled(enabled: Boolean) {
        on.value = enabled
        inbox.setPolicy(CommsPolicy(enabled = enabled, allowedPackages = allowed.value))
        historyStore.setRecording(enabled && keep.value)
        prefs.setCommsEnabled(enabled)
    }

    override suspend fun setCannedReplies(replies: List<String>) {
        val cleaned = CommsCannedReplies.sanitize(replies)
        canned.value = cleaned
        prefs.setCommsCannedReplies(cleaned)
    }

    override suspend fun setKeepHistory(enabled: Boolean) {
        keep.value = enabled
        historyStore.setRecording(on.value && enabled)
        prefs.setCommsKeepHistory(enabled)
    }

    override suspend fun clearHistory() {
        withContext(io) { historyStore.clear() }
    }

    override suspend fun deleteHistoryMessage(id: Long) {
        withContext(io) { historyStore.delete(id) }
    }

    override suspend fun deleteHistoryConversation(app: String, conversationKey: String) {
        withContext(io) { historyStore.deleteConversation(app, conversationKey) }
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
     * Cancels notifications. The messages are already stored from the post (T214); this does not
     * write or delete a history row. Used by the dismiss overloads and by the mark-as-read
     * fallback.
     */
    private fun dismissKeys(keys: Collection<String>): MarkReadResult {
        if (keys.isEmpty()) return MarkReadResult.Unavailable
        for (key in keys) actions.dismiss(key)
        return MarkReadResult.Dismissed
    }
}

/** One DataStore snapshot, so the four settings are applied in a single collector step. */
private data class CommsSettingsSnapshot(
    val enabled: Boolean,
    val allowedPackages: Set<String>,
    val cannedReplies: List<String>,
    val keepHistory: Boolean,
)

/**
 * Maps a stored row to the existing T77 history line. Image data does not exist in the entity, so
 * [CommsMessage.image] is always null; links are decoded with the store's validation.
 */
private fun CommsMessageEntity.toHistoryEntry(): CommsHistoryEntry = CommsHistoryEntry(
    conversation = CommsConversationId(app, conversationKey),
    title = conversationTitle,
    message = CommsMessage(
        notificationKey = notificationKey,
        sender = sender,
        text = text,
        timeMs = timeMs,
        image = null,
        links = linkSpans,
        canReply = false,
        canMarkRead = false,
    ),
    removedAtMs = removedAtMs ?: recordedAtMs,
)

/** Builds the production Comms repository for `AppGraph` (wiring lives in `:app`, T56). */
object CommsRepositories {

    /**
     * Binds the shared inbox/action/history stores and returns the repository. The caller owns
     * [scope] and keeps it for the process lifetime, because it mirrors the DataStore settings into
     * the inbox and the history store. Call once per process next to the other AppGraph services.
     */
    fun create(
        context: Context,
        prefs: ParcPrefs,
        scope: CoroutineScope,
        historyStore: CommsHistoryStore = CommsHistoryStore.shared(context),
    ): CommsExtrasRepository {
        val actions = AndroidCommsActions.shared
        actions.bindContext(context.applicationContext)
        return CommsRepositoryImpl(
            inbox = CommsInbox.shared,
            prefs = prefs,
            actions = actions,
            io = Dispatchers.IO,
            scope = scope,
            historyStore = historyStore,
        )
    }
}
