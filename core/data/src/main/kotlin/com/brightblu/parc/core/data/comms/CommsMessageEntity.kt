package com.brightblu.parc.core.data.comms

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stored message (T214). This is the only shape message history takes on disk: text and link
 * spans, never an image, bitmap, URI or thumbnail. [links] is the JSON array of `[start,
 * endExclusive]` spans described in the card; [CommsStoredLinks] is the only codec.
 *
 * [identity] is the stable dedupe key. [live] is a hint, not the authority: the live notification
 * key map decides whether a row can still be replied to. [removedAtMs] is null while the row is
 * live and the notification key is cleared to the empty string by the store when it goes away.
 */
@Entity(
    tableName = "comms_messages",
    indices = [
        Index(value = ["identity"], unique = true),
        Index(
            value = ["app", "conversation_key", "time_ms"],
            name = "index_comms_messages_conversation",
        ),
        Index(value = ["time_ms"]),
        Index(value = ["live"]),
    ],
)
data class CommsMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "app")
    val app: String,
    @ColumnInfo(name = "conversation_key")
    val conversationKey: String,
    @ColumnInfo(name = "conversation_title")
    val conversationTitle: String,
    @ColumnInfo(name = "sender", defaultValue = "''")
    val sender: String = "",
    @ColumnInfo(name = "notification_title", defaultValue = "''")
    val notificationTitle: String = "",
    @ColumnInfo(name = "text", defaultValue = "''")
    val text: String = "",
    @ColumnInfo(name = "links", defaultValue = "'[]'")
    val links: String = "[]",
    @ColumnInfo(name = "time_ms")
    val timeMs: Long,
    @ColumnInfo(name = "recorded_at_ms")
    val recordedAtMs: Long,
    @ColumnInfo(name = "notification_key")
    val notificationKey: String,
    @ColumnInfo(name = "identity")
    val identity: String,
    @ColumnInfo(name = "live", defaultValue = "1")
    val live: Boolean = true,
    @ColumnInfo(name = "removed_at_ms")
    val removedAtMs: Long? = null,
) {
    /**
     * The valid link spans into [text]. Use this rather than parsing [links]: malformed JSON or an
     * out-of-range span is dropped and the text is kept.
     */
    val linkSpans: List<CommsLink>
        get() = CommsStoredLinks.decode(text, links)
}
