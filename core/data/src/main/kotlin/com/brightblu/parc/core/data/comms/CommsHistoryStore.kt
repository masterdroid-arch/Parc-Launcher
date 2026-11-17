package com.brightblu.parc.core.data.comms

import android.content.Context
import com.brightblu.parc.core.data.lock.NotificationLockPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest

/**
 * The one reader/writer of the persistent message history (T214). A process-wide instance is
 * created from the application context with [shared]; the notification listener and the repository
 * both use it, so there is never a second connection to `comms-history.db`.
 *
 * Rules pinned by the card:
 * - only [RecordedPackages] can be written, and only while recording is on and the package is
 *   still on the live allow-list;
 * - write on post, never only on removal; removal and disconnect only set `live = 0`;
 * - no automatic deletion, TTL or silent cap. [clear], [delete] and [deleteConversation] are the
 *   only methods that remove rows;
 * - text and link spans only. An image-only line with no caption is skipped;
 * - [record], [markRemoved] and [markAllNotLive] are plain calls for the listener's
 *   `parc-notifications` worker. [clear]/[delete]/[deleteConversation] are suspend and the
 *   repository runs them on IO. Room's `allowMainThreadQueries` is not enabled.
 */
class CommsHistoryStore internal constructor(
    private val dao: CommsMessageDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var recording = true

    @Volatile
    private var allowedPackages: Set<String> = RecordedPackages

    /** Stored rows, newest message first. Invalid link spans are dropped when decoded. */
    val messages: Flow<List<CommsMessageEntity>> = dao.observeAll()

    /** Pauses or resumes writes. Turning recording off never touches the stored rows. */
    internal fun setRecording(enabled: Boolean) {
        recording = enabled
    }

    /**
     * Updates which live apps may add rows. Removing an app from this set stops new writes for it
     * and keeps every row already stored.
     */
    internal fun setAllowedPackages(packages: Set<String>) {
        allowedPackages = packages.toSet()
    }

    /**
     * Writes every message of an accepted notification, deduped by [CommsHistoryIdentity]. Returns
     * how many rows were new. A repost refreshes the live notification key without adding a row.
     * [postedAtMs] is when Parc first stored the message, not when it was sent.
     */
    internal fun record(parsed: ParsedNotification, postedAtMs: Long = clock()): Int {
        if (!recording || parsed.app !in RecordedPackages || parsed.app !in allowedPackages) return 0
        if (NotificationLockPolicy.blocked(parsed.app)) return 0
        var written = 0
        for (message in parsed.messages) {
            // An image-only message with no caption has nothing readable to store.
            if (message.text.isBlank() && message.links.isEmpty()) continue
            val identity = CommsHistoryIdentity.of(
                app = parsed.app,
                conversationKey = parsed.conversationKey,
                sender = message.sender,
                timeMs = message.timeMs,
                text = message.text,
            )
            if (dao.findByIdentity(identity) != null) {
                // A repost of the same message: keep one row, refresh its live notification key.
                dao.markLive(identity, parsed.notificationKey, parsed.title)
                continue
            }
            val inserted = dao.insert(
                CommsMessageEntity(
                    app = parsed.app,
                    conversationKey = parsed.conversationKey,
                    conversationTitle = parsed.title,
                    sender = message.sender,
                    notificationTitle = parsed.title,
                    text = message.text,
                    links = CommsStoredLinks.encode(message.text, message.links),
                    timeMs = message.timeMs,
                    recordedAtMs = postedAtMs,
                    notificationKey = parsed.notificationKey,
                    identity = identity,
                    live = true,
                    removedAtMs = null,
                ),
            )
            if (inserted != -1L) {
                written++
            } else {
                // A unique-index race can only come from a duplicate; treat it as a repost.
                dao.markLive(identity, parsed.notificationKey, parsed.title)
            }
        }
        return written
    }

    /** A notification left: keep the row and mark it stale. */
    internal fun markRemoved(notificationKey: String, removedAtMs: Long = clock()): Int =
        dao.markRemoved(notificationKey, removedAtMs)

    /** Disconnect/connect reconciliation: no row is deleted, only its live hint is cleared. */
    internal fun markAllNotLive(removedAtMs: Long = clock()): Int =
        dao.markAllNotLive(removedAtMs)

    /** Deletes every stored message. Only an explicit user action may call this. */
    suspend fun clear() {
        dao.clear()
    }

    /** Deletes one stored message by row id. */
    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    /** Deletes one conversation's rows. */
    suspend fun deleteConversation(app: String, conversationKey: String) {
        dao.deleteConversation(app, conversationKey)
    }

    /** The row's valid link spans; malformed stored JSON or out-of-range spans are dropped. */
    fun linksOf(message: CommsMessageEntity): List<CommsLink> = message.linkSpans

    companion object {
        /** The history file; deliberately not the layout database (`parc.db`). */
        const val DatabaseName = "comms-history.db"

        /**
         * The only three packages ever recorded by the history policy. Live cards may opt in
         * to other apps; history may not.
         */
        val RecordedPackages: Set<String> = linkedSetOf(
            CommsPackages.WhatsApp,
            CommsPackages.Telegram,
            CommsPackages.Instagram,
        )

        @Volatile
        private var sharedInstance: CommsHistoryStore? = null

        /**
         * The process-wide store. The platform constructs the listener itself, so the service and
         * the repository meet here, exactly like [CommsInbox.shared].
         */
        fun shared(context: Context): CommsHistoryStore =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: CommsHistoryStore(
                    CommsHistoryDatabase.create(context.applicationContext).messageDao(),
                ).also { sharedInstance = it }
            }
    }
}

/**
 * JSON codec for [CommsMessageEntity.links]: an array of `[start, endExclusive]` span pairs into the
 * message text. On read, malformed JSON, a wrong shape or an out-of-range span is dropped and the
 * text is kept. Pure, no Android.
 */
internal object CommsStoredLinks {

    fun encode(text: String, links: List<CommsLink>): String {
        val valid = links
            .filter { it.start >= 0 && it.endExclusive <= text.length && it.endExclusive > it.start }
            .distinct()
            .sortedWith(compareBy({ it.start }, { it.endExclusive }))
        return JsonArray(
            valid.map { link ->
                JsonArray(listOf(JsonPrimitive(link.start), JsonPrimitive(link.endExclusive)))
            },
        ).toString()
    }

    fun decode(text: String, links: String): List<CommsLink> {
        val root = runCatching { Json.parseToJsonElement(links) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return root.mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            if (pair.size != 2) return@mapNotNull null
            val start = (pair[0] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (pair[1] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (start < 0 || end <= start || end > text.length) return@mapNotNull null
            runCatching { CommsLink(start, end) }.getOrNull()
        }.distinct().sortedWith(compareBy({ it.start }, { it.endExclusive }))
    }
}

/**
 * The stable dedupe identity: lowercase hex SHA-256 of
 * `app\0conversationKey\0sender\0timeMs\0text`. A repost has the same identity; an edited body at
 * the same timestamp deliberately gets a new row.
 */
internal object CommsHistoryIdentity {

    fun of(app: String, conversationKey: String, sender: String, timeMs: Long, text: String): String {
        val joined = buildString {
            append(app)
            append('\u0000')
            append(conversationKey)
            append('\u0000')
            append(sender)
            append('\u0000')
            append(timeMs)
            append('\u0000')
            append(text)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xFF
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(hex)
    }
}
