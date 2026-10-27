package com.brightblu.parc

import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.model.Metrics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CommsPresentation {
    fun tag(app: String): String = when (app) {
        CommsPackages.Telegram -> "telegram"
        CommsPackages.WhatsApp -> "whatsapp"
        CommsPackages.Instagram -> "instagram"
        CommsPackages.Messages -> "messages"
        CommsPackages.Signal -> "signal"
        CommsPackages.Discord -> "discord"
        else -> app.substringAfterLast('.').take(16).lowercase(Locale.ROOT)
    }
    fun header(conversation: CommsConversation): String = header(conversation.app, conversation.title)

    /** The card header for an app and title that no longer has a live conversation (T77 history). */
    fun header(app: String, title: String): String =
        "${tag(app).uppercase(Locale.ROOT)} · ${title.replace('\n', ' ').replace('\r', ' ')}"

    fun time(message: CommsMessage, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(message.timeMs))

    /** A card line is the local time plus the text; the sender/app live only in the card header. */
    fun lineParts(message: CommsMessage, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> =
        time(message, zone) to message.text

    fun cards(conversations: List<CommsConversation>): List<CommsConversation> =
        conversations.sortedByDescending { it.updatedAtMs }

    fun recent(conversation: CommsConversation): List<CommsMessage> =
        conversation.messages.sortedByDescending { it.timeMs }.take(Metrics.commsRecentMessages).reversed()

    fun selection(selected: CommsConversationId?, conversations: List<CommsConversation>): CommsConversationId? =
        selected?.takeIf { id -> conversations.any { it.id == id } }

    // TextLayoutResult supplies measured soft-wrap boundaries at the actual screen/font width.
    fun visibleEnd(lineEnds: List<Int>, expanded: Boolean): Int? =
        if (expanded || lineEnds.size <= Metrics.commsMaxLines) null else lineEnds[Metrics.commsMaxLines - 1]

    fun notice(result: ReplyResult): String = when (result) {
        ReplyResult.Sent -> "sent"
        ReplyResult.NoReplyAction -> "no reply button, opens app"
        ReplyResult.Failed -> "reply failed, opens app"
    }
}
