package com.brightblu.parc

import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.core.data.notifications.NotificationDrop
import com.brightblu.parc.model.Metrics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The hero above the live cards: only shown when there is more than one conversation. */
internal data class CommsHero(val value: String, val label: String)

/** One package-only row of the `Why?` sheet. */
internal data class CommsWhyRow(val packageName: String, val reason: String)

internal object CommsPresentation {
    fun dismissLabel(all: Boolean, terminal: Boolean): String = when {
        terminal && all -> "[x all]"
        terminal -> "[x]"
        all -> "Dismiss all"
        else -> "Dismiss"
    }

    fun quickRepliesLabel(terminal: Boolean): String = if (terminal) "[r]" else "Quick replies"

    fun imageLabel(terminal: Boolean): String = if (terminal) "[img]" else "Image"

    fun tag(app: String): String = when (app) {
        CommsPackages.Telegram -> "telegram"
        CommsPackages.WhatsApp -> "whatsapp"
        CommsPackages.Instagram -> "instagram"
        CommsPackages.Messages -> "messages"
        CommsPackages.Signal -> "signal"
        CommsPackages.Discord -> "discord"
        else -> app.substringAfterLast('.').take(16).lowercase(Locale.ROOT)
    }

    /** A human app name for actions and empty-state copy. Unknown packages use their last segment. */
    fun appName(app: String): String = when (app) {
        CommsPackages.Telegram -> "Telegram"
        CommsPackages.WhatsApp -> "WhatsApp"
        CommsPackages.Instagram -> "Instagram"
        CommsPackages.Messages -> "Messages"
        CommsPackages.Signal -> "Signal"
        CommsPackages.Discord -> "Discord"
        CommsPackages.Imo -> "imo"
        else -> app.substringAfterLast('.').replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    fun openLabel(app: String): String = "Open ${appName(app)}"

    fun header(conversation: CommsConversation): String = header(conversation.app, conversation.title)

    /** The card header for an app and title that no longer has a live conversation (T77 history). */
    fun header(app: String, title: String): String =
        "${tag(app).uppercase(Locale.ROOT)} · ${title.replace('\n', ' ').replace('\r', ' ')}"

    /** T233: the compact one-line page header, `Messages · 2 live`. */
    fun headerLine(live: Int): String = "Messages · $live live"

    /** The newest message in a conversation, or null when the inbox row has no messages. */
    fun latest(conversation: CommsConversation): CommsMessage? =
        conversation.messages.maxByOrNull { it.timeMs }

    /**
     * T233: the latest message body shown on a card. It is the stored text (a caption for an
     * image) and is never bracketed or replaced by an image label; the UI caps the visible lines.
     */
    fun latestText(conversation: CommsConversation): String = latest(conversation)?.text.orEmpty()

    /** The conversation title, falling back to the newest message's sender. Line breaks are flattened. */
    fun sender(conversation: CommsConversation): String =
        (conversation.title.ifBlank { latest(conversation)?.sender.orEmpty() })
            .replace('\n', ' ').replace('\r', ' ')

    /** T233: `Reply` only when the live notification supports it, otherwise `Open <app>`. */
    fun liveActionLabel(conversation: CommsConversation): String =
        if (conversation.canReply) "Reply" else openLabel(conversation.app)

    fun time(message: CommsMessage, zone: ZoneId = ZoneId.systemDefault()): String =
        time(message.timeMs, zone)

    fun time(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(timeMs))

    /** T233: `Today`, `Yesterday`, `3 Nov`, or the year too when it is not this year. */
    fun dateLabel(timeMs: Long, zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)): String {
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> DateTimeFormatter.ofPattern("d MMM", Locale.ROOT).format(date)
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT).format(date)
        }
    }

    /** T233: History cards newest first, independent of the conversation grouping used by paging. */
    fun historyCards(groups: List<CommsHistoryGroup>): List<CommsHistoryMessage> =
        groups.flatMap { it.messages }
            .sortedWith(compareByDescending<CommsHistoryMessage> { it.timeMs }.thenByDescending { it.id })

    /** T233: History cards under one date header, each day's cards newest first. */
    fun historySections(cards: List<CommsHistoryMessage>, zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone)): List<CommsHistorySection> {
        if (cards.isEmpty()) return emptyList()
        val sections = ArrayList<CommsHistorySection>()
        for (card in cards) {
            val label = dateLabel(card.timeMs, zone, today)
            if (sections.isNotEmpty() && sections.last().label == label) {
                sections[sections.lastIndex] = sections.last().copy(messages = sections.last().messages + card)
            } else {
                sections += CommsHistorySection(label, listOf(card))
            }
        }
        return sections
    }

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

    /** `1 conversation` / `N conversations`; the page's hero is hidden at 0 and 1. */
    fun conversationCount(count: Int): String =
        if (count == 1) "1 conversation" else "$count conversations"

    /** The card badge: `1 message` / `14 messages`. */
    fun messageCount(count: Int): String = if (count == 1) "1 message" else "$count messages"

    /** The hero only earns its place above one live conversation. */
    fun hero(count: Int): CommsHero? =
        if (count > 1) CommsHero(count.toString(), "conversations") else null

    fun listeningSince(connectedAtMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (connectedAtMs == null) "Listening now"
        else "Listening since " + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .withZone(zone).format(Instant.ofEpochMilli(connectedAtMs))

    /** `Listening since 14:02 · access on · 1 live · 3 stored`. The count is the kept history. */
    fun footer(connectedAtMs: Long?, accessOn: Boolean, live: Int, stored: Int,
        zone: ZoneId = ZoneId.systemDefault()): String =
        "${listeningSince(connectedAtMs, zone)} · ${if (accessOn) "access on" else "access off"} · " +
            "$live live · $stored stored"

    /**
     * The Why sheet's rows, package and reason only. Access off has no posting package, so it is
     * named once as `All apps`; the ring itself still never stores content.
     */
    fun whyRows(drops: List<NotificationDrop>, accessOn: Boolean): List<CommsWhyRow> {
        val rows = drops.map { CommsWhyRow(it.packageName, it.reason.label) }
        return (if (accessOn) rows else listOf(CommsWhyRow("All apps", CommsDropReason.AccessOff.label)) + rows)
            .take(NotificationListenerRows)
    }

    private const val NotificationListenerRows = 5
}
