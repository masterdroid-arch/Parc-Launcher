package com.brightblu.parc.core.data.comms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Small builders for the pure Comms tests; every default is a notification Comms should accept. */
internal fun rawNotification(
    packageName: String = CommsPackages.Telegram,
    key: String = "n1",
    isOngoing: Boolean = false,
    flags: Int = 0,
    visibility: Int = 0,
    category: String? = CommsCategories.Message,
    title: String? = "Sam",
    text: String? = "hello",
    bigText: String? = null,
    conversationTitle: String? = null,
    isGroupConversation: Boolean = false,
    postTimeMs: Long = 1_000L,
    messages: List<RawMessage> = emptyList(),
    actions: List<RawAction> = emptyList(),
    picture: CommsImage? = null,
): RawNotification = RawNotification(
    packageName = packageName,
    key = key,
    isOngoing = isOngoing,
    flags = flags,
    visibility = visibility,
    category = category,
    title = title,
    text = text,
    bigText = bigText,
    conversationTitle = conversationTitle,
    isGroupConversation = isGroupConversation,
    postTimeMs = postTimeMs,
    messages = messages,
    actions = actions,
    picture = picture,
)

internal fun rawMessage(
    sender: String? = "Sam",
    text: String? = "hello",
    timeMs: Long = 1_000L,
    dataUri: String? = null,
    image: CommsImage? = null,
): RawMessage = RawMessage(sender, text, timeMs, dataUri, image)

internal fun replyAction(): RawAction = RawAction(hasFreeFormInput = true)

/** The platform's mark-as-read semantic (2) on an action with no input. */
internal fun markReadAction(): RawAction = RawAction(
    hasFreeFormInput = false,
    semanticAction = CommsReplyPlanner.MarkAsReadSemantic,
)

/** A uniform image, useful when a test only cares that a picture is attached. */
internal fun grayImage(argb: Int, width: Int = 2, height: Int = 2): CommsImage =
    OrderedDither.fromPixels(IntArray(width * height) { argb }, width, height)

/** Records every platform call so repository tests assert the exact keys and text. */
internal class FakeCommsActions : CommsActions {

    val replies = mutableListOf<Pair<String, String>>()
    val dismissed = mutableListOf<String>()
    val markedRead = mutableListOf<String>()
    var result: ReplyResult = ReplyResult.Sent
    var markReadResult: MarkReadResult = MarkReadResult.Marked

    override fun reply(notificationKey: String, text: String): ReplyResult {
        replies += notificationKey to text
        return result
    }

    override fun dismiss(notificationKey: String) {
        dismissed += notificationKey
    }

    override fun markAsRead(notificationKey: String): MarkReadResult {
        markedRead += notificationKey
        return markReadResult
    }
}

/**
 * A synchronous stand-in for Room's generated DAO (T214). It keeps the rows in the same shape and
 * lets a test reopen a store over the same "database" to prove the persistence contract without an
 * Android SQLite runtime in JVM unit tests.
 */
internal class FakeCommsMessageDao(initial: List<CommsMessageEntity> = emptyList()) : CommsMessageDao {

    private var rows: MutableList<CommsMessageEntity> = initial.toMutableList()
    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    private val state = MutableStateFlow(sorted(rows))

    fun stored(): List<CommsMessageEntity> = sorted(rows)

    private fun sorted(list: List<CommsMessageEntity>): List<CommsMessageEntity> =
        list.sortedWith(compareByDescending<CommsMessageEntity> { it.timeMs }.thenByDescending { it.id })

    private fun publish() {
        state.value = sorted(rows)
    }

    @Synchronized
    override fun insert(message: CommsMessageEntity): Long {
        if (rows.any { it.identity == message.identity }) return -1L
        val stored = message.copy(id = nextId++)
        rows += stored
        publish()
        return stored.id
    }

    @Synchronized
    override fun findByIdentity(identity: String): CommsMessageEntity? =
        rows.firstOrNull { it.identity == identity }

    @Synchronized
    override fun markLive(identity: String, notificationKey: String, conversationTitle: String): Int {
        var changed = 0
        rows = rows.map {
            if (it.identity != identity) it else {
                changed++
                it.copy(
                    notificationKey = notificationKey,
                    conversationTitle = conversationTitle,
                    live = true,
                    removedAtMs = null,
                )
            }
        }.toMutableList()
        if (changed > 0) publish()
        return changed
    }

    @Synchronized
    override fun markRemoved(notificationKey: String, removedAtMs: Long): Int {
        var changed = 0
        rows = rows.map {
            if (it.notificationKey != notificationKey || !it.live) it else {
                changed++
                it.copy(live = false, removedAtMs = removedAtMs, notificationKey = "")
            }
        }.toMutableList()
        if (changed > 0) publish()
        return changed
    }

    @Synchronized
    override fun markAllNotLive(removedAtMs: Long): Int {
        var changed = 0
        rows = rows.map {
            if (!it.live) it else {
                changed++
                it.copy(live = false, removedAtMs = removedAtMs, notificationKey = "")
            }
        }.toMutableList()
        if (changed > 0) publish()
        return changed
    }

    override suspend fun clear() {
        rows.clear()
        publish()
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
        publish()
    }

    override suspend fun deleteConversation(app: String, conversationKey: String) {
        rows.removeAll { it.app == app && it.conversationKey == conversationKey }
        publish()
    }

    override fun observeAll(): Flow<List<CommsMessageEntity>> = state
}
