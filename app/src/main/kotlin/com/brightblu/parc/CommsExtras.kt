package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsCannedReplies
import com.brightblu.parc.core.data.comms.CommsConversation
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.comms.CommsLink
import com.brightblu.parc.core.data.comms.CommsMessageEntity
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.core.data.comms.MarkReadResult
import java.util.Locale

/**
 * What the Comms page is showing (T77, T215). [History] and [NoHistory] are the stored-message
 * view reached from the segmented Live/History control; [Empty] is Comms off, access off in Live,
 * or no active message. Pure so the whole page state is testable without Compose.
 */
internal enum class CommsMode { Live, Empty, History, NoHistory }

/** The two segments of the Messages page (T215). The stored list replaces the old bracket toggle. */
internal enum class CommsSegment(val label: String) { Live("Live"), History("History") }

/**
 * One stored row as the History view reads it. There is deliberately no image field: the store
 * keeps text and link spans only, so a captioned image shows its caption and an image without one
 * never reaches this model.
 */
internal data class CommsHistoryMessage(
    val id: Long,
    val app: String,
    val conversationKey: String,
    val conversationTitle: String,
    val sender: String,
    val text: String,
    val links: List<CommsLink>,
    val timeMs: Long,
    val live: Boolean,
    val notificationKey: String,
) {
    val conversation: CommsConversationId get() = CommsConversationId(app, conversationKey)
}

/** One conversation group in History, messages newest first. */
internal data class CommsHistoryGroup(
    val app: String,
    val conversationKey: String,
    val title: String,
    val messages: List<CommsHistoryMessage>,
) {
    val conversation: CommsConversationId get() = CommsConversationId(app, conversationKey)
}

/** T233: stored messages under one date header (`Today`, `Yesterday`, `3 Nov`). */
internal data class CommsHistorySection(
    val label: String,
    val messages: List<CommsHistoryMessage>,
)

/** What a stored line can do: reply through a still-live notification, or open its app. */
internal enum class CommsHistoryAction { Reply, Open }

/** The single action of a stored line plus, for [Open], the one line that explains why. */
internal data class CommsHistoryActionState(val action: CommsHistoryAction, val status: String? = null)

/**
 * The reply controls a single card carries. The field and the chips follow the selection (the card
 * the user opened) *and* [canReply], so an app without a RemoteInput action never shows a dead
 * field. Chips exist only when the canned list has entries. Read and dismiss-all are always offered
 * on a live card.
 */
internal data class CommsCardState(
    val selected: Boolean,
    val canReply: Boolean,
    val showField: Boolean,
    val showQuickReplies: Boolean,
    val quickReplies: List<String>,
)

/** The one reason the empty Messages page names, in priority order. */
internal enum class CommsEmptyReason { AccessOff, MessagesOff, FocusHidden, NoLive, AllFiltered }

/** The one action an empty page offers; null only when there is genuinely nothing to do. */
internal enum class CommsEmptyAction { Allow, TurnOn, OpenHistory, OpenFocus, Why }

/** One reason, one action and, for access off, the history note the card asks for. */
internal data class CommsEmptyState(
    val reason: CommsEmptyReason,
    val message: String,
    val action: CommsEmptyAction,
    val actionLabel: String,
    val note: String? = null,
)

/** A picker row: which group it belongs to, and the package/label shown. */
internal enum class CommsAppGroup(val label: String) {
    Installed("Installed messaging and social apps"),
    Fixed("Supported apps"),
    Other("Other apps"),
}

internal data class CommsAppChoice(val packageName: String, val label: String, val group: CommsAppGroup)

/** One installed app, with the classification the picker needs to put messaging/social first. */
internal data class CommsAppEntry(
    val packageName: String,
    val label: String,
    val messagingOrSocial: Boolean = false,
)

/** Where a live status line is drawn (T77). [Page] covers a card that just left the list. */
internal enum class CommsNoticePlacement { None, Card, Page }

/**
 * T77 presentation rules. Everything here is pure: no Android, no Compose, no allocation in a draw
 * loop (the UI calls these during composition, never while drawing).
 */
internal object CommsExtrasPresentation {

    /**
     * The page mode for the selected segment (T215). History never depends on access or the live
     * switch: stored rows stay readable while notification access is off. Live falls back to the
     * one T213 empty reason when there is nothing to show.
     */
    fun mode(segment: CommsSegment, accessOn: Boolean, enabled: Boolean, conversations: Int, stored: Int): CommsMode =
        when (segment) {
            CommsSegment.History -> if (stored > 0) CommsMode.History else CommsMode.NoHistory
            CommsSegment.Live -> when {
                !enabled || !accessOn -> CommsMode.Empty
                conversations > 0 -> CommsMode.Live
                else -> CommsMode.Empty
            }
        }

    /** `Live` / `History`; a terminal skin may use its bracket language. */
    fun segmentLabel(segment: CommsSegment, terminal: Boolean = false): String =
        if (terminal) "[${segment.label.lowercase(Locale.ROOT)}]" else segment.label

    /** Access off opens on History, because the stored rows are the only thing still readable. */
    fun defaultSegment(accessOn: Boolean): CommsSegment =
        if (accessOn) CommsSegment.Live else CommsSegment.History

    /** The card's reply controls for the current selection, canned list and reply capability. */
    fun cardState(selected: Boolean, cannedReplies: List<String>, canReply: Boolean = true): CommsCardState {
        val showField = selected && canReply
        return CommsCardState(
            selected = selected,
            canReply = canReply,
            showField = showField,
            showQuickReplies = showField && cannedReplies.isNotEmpty(),
            quickReplies = cannedReplies,
        )
    }

    /**
     * Picks the single empty reason and action. Access off wins over everything; Messages off is
     * next. A page with live cards has no empty state. Focus-hidden cards are named only when there
     * really were messages from those apps. History beats the filter explanation because its action
     * is useful; the Why sheet is the fallback when nothing is stored.
     */
    fun emptyState(
        accessOn: Boolean,
        enabled: Boolean,
        live: Int,
        stored: Int,
        focusMode: String? = null,
        focusHiddenApps: List<String> = emptyList(),
        drops: Int = 0,
    ): CommsEmptyState? = when {
        !accessOn -> CommsEmptyState(
            reason = CommsEmptyReason.AccessOff,
            message = "Notification access is off",
            action = CommsEmptyAction.Allow,
            actionLabel = "Allow",
            note = historyNote(stored),
        )
        !enabled -> CommsEmptyState(
            reason = CommsEmptyReason.MessagesOff,
            message = "Messages are off",
            action = CommsEmptyAction.TurnOn,
            actionLabel = "Turn on",
            note = historyNote(stored),
        )
        live > 0 -> null
        focusMode != null && focusHiddenApps.isNotEmpty() -> CommsEmptyState(
            reason = CommsEmptyReason.FocusHidden,
            message = "Focus mode $focusMode is hiding ${CommsPresentation.appName(focusHiddenApps.first())}",
            action = CommsEmptyAction.OpenFocus,
            actionLabel = "Open Focus",
        )
        stored > 0 -> CommsEmptyState(
            reason = CommsEmptyReason.NoLive,
            message = "No live messages. History shows what was kept on this phone.",
            action = CommsEmptyAction.OpenHistory,
            actionLabel = "Open history",
        )
        drops > 0 -> CommsEmptyState(
            reason = CommsEmptyReason.AllFiltered,
            message = "Nothing has arrived since Parc started listening.",
            action = CommsEmptyAction.Why,
            actionLabel = "Why?",
        )
        else -> CommsEmptyState(
            reason = CommsEmptyReason.NoLive,
            message = "No live messages. History shows what was kept on this phone.",
            action = CommsEmptyAction.Why,
            actionLabel = "Why?",
        )
    }

    private fun historyNote(stored: Int): String? = when {
        stored <= 0 -> null
        stored == 1 -> "1 stored message is still readable in History."
        else -> "$stored stored messages are still readable in History."
    }

    // ---- T215 stored History ---------------------------------------------------------------

    /** The number of rows one page shows before `Show older`. Display only; nothing is deleted. */
    const val HistoryPageSize = 200

    /**
     * The stored list, newest first and grouped by conversation. Groups keep the order of their
     * newest row, and each group's messages are newest first. [query] matches message text, sender
     * or conversation title, case-insensitively. Image-only rows never enter the model: the store
     * already skips them, and this also keeps a hand-written test row with no readable text out.
     */
    fun historyGroups(rows: List<CommsMessageEntity>, query: String = ""): List<CommsHistoryGroup> {
        val needle = query.trim()
        val filtered = rows.asSequence()
            .filter { it.text.isNotBlank() || it.linkSpans.isNotEmpty() }
            .filter { row ->
                needle.isEmpty() ||
                    row.text.contains(needle, ignoreCase = true) ||
                    row.sender.contains(needle, ignoreCase = true) ||
                    row.conversationTitle.contains(needle, ignoreCase = true)
            }
            .sortedWith(compareByDescending<CommsMessageEntity> { it.timeMs }.thenByDescending { it.id })
            .toList()
        if (filtered.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<CommsConversationId, MutableList<CommsHistoryMessage>>()
        for (row in filtered) {
            val id = CommsConversationId(row.app, row.conversationKey)
            grouped.getOrPut(id) { ArrayList() }.add(row.toHistoryMessage())
        }
        return grouped.map { (id, messages) ->
            CommsHistoryGroup(id.app, id.key, messages.first().conversationTitle, messages)
        }
    }

    /** The first [limit] stored messages across [groups], preserving group and message order. */
    fun historyPage(groups: List<CommsHistoryGroup>, limit: Int = HistoryPageSize): List<CommsHistoryGroup> {
        if (limit <= 0) return emptyList()
        var remaining = limit
        val page = ArrayList<CommsHistoryGroup>()
        for (group in groups) {
            if (remaining <= 0) break
            val take = minOf(remaining, group.messages.size)
            page += group.copy(messages = group.messages.take(take))
            remaining -= take
        }
        return page
    }

    /** How many stored rows [groups] hold; `Show older` compares this with the visible page. */
    fun historyTotal(groups: List<CommsHistoryGroup>): Int = groups.sumOf { it.messages.size }

    /**
     * A stored line may reply only through a notification that is still live *and* exposes a reply
     * action. Otherwise it opens the app, with the honest line: gone, or the T213 no-reply copy.
     */
    fun historyAction(message: CommsHistoryMessage, conversations: List<CommsConversation>): CommsHistoryActionState {
        val live = conversations.firstOrNull { it.id == message.conversation }
            ?.messages?.firstOrNull { it.notificationKey == message.notificationKey }
        return when {
            message.live && live?.canReply == true -> CommsHistoryActionState(CommsHistoryAction.Reply)
            message.live && live != null -> CommsHistoryActionState(
                CommsHistoryAction.Open, "This app does not allow replies here.",
            )
            else -> CommsHistoryActionState(CommsHistoryAction.Open, "notification is gone")
        }
    }

    /** The Messages settings storage line: `312 messages · 180 KB`; always an explicit count. */
    fun storageLine(count: Int, bytes: Long): String =
        "${CommsPresentation.messageCount(count)} · ${storageSize(bytes)}"

    /** Whole KB, rounded up, so a small store never reads as zero when it holds rows. */
    fun storageSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = (bytes + 1023L) / 1024L
        return "$kb KB"
    }

    /** A stable size estimate from the stored fields, never from image data (there is none). */
    fun storageBytes(rows: List<CommsMessageEntity>): Long = rows.fold(0L) { total, row ->
        total + StorageRowOverheadBytes +
            row.app.toByteArray(Charsets.UTF_8).size +
            row.conversationKey.toByteArray(Charsets.UTF_8).size +
            row.conversationTitle.toByteArray(Charsets.UTF_8).size +
            row.sender.toByteArray(Charsets.UTF_8).size +
            row.notificationTitle.toByteArray(Charsets.UTF_8).size +
            row.text.toByteArray(Charsets.UTF_8).size +
            row.links.toByteArray(Charsets.UTF_8).size
    }

    private const val StorageRowOverheadBytes = 64L

    private fun CommsMessageEntity.toHistoryMessage(): CommsHistoryMessage = CommsHistoryMessage(
        id = id,
        app = app,
        conversationKey = conversationKey,
        conversationTitle = conversationTitle,
        sender = sender,
        text = text,
        links = linkSpans,
        timeMs = timeMs,
        live = live,
        notificationKey = notificationKey,
    )

    /** The one-line status after `[read]`; every outcome is named, never silent. */
    fun readNotice(result: MarkReadResult): String = when (result) {
        MarkReadResult.Marked -> "read"
        MarkReadResult.Dismissed -> "read, notifications cleared"
        MarkReadResult.Unavailable -> "nothing to read"
        MarkReadResult.Failed -> "read failed"
    }

    /**
     * A status line belongs beside its card while that card is in the list. Marking a conversation
     * read through the dismiss fallback removes the card, so the line moves to the page instead of
     * vanishing with it.
     */
    fun noticePlacement(app: String?, liveApps: Collection<String>): CommsNoticePlacement = when {
        app == null -> CommsNoticePlacement.None
        app in liveApps -> CommsNoticePlacement.Card
        else -> CommsNoticePlacement.Page
    }
}

/**
 * The Messages apps picker order (T213): installed messaging/social apps first, then the fixed
 * opt-in list, then every other installed or already-allowed app under `Other apps`. Within a group
 * the order is by label; the groups are never flattened into one alphabetical list.
 */
internal object CommsAppPicker {

    fun choices(
        installed: List<CommsAppEntry>,
        allowed: Set<String> = emptySet(),
        fixed: List<String> = CommsPackages.FixedOptIns,
    ): List<CommsAppChoice> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<CommsAppChoice>()
        val labels = installed.associate { it.packageName to it.label }

        fun add(packageName: String, label: String, group: CommsAppGroup) {
            if (packageName.isBlank() || !seen.add(packageName)) return
            out += CommsAppChoice(packageName, label.ifBlank { CommsPresentation.appName(packageName) }, group)
        }

        installed.filter { it.messagingOrSocial }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .forEach { add(it.packageName, it.label, CommsAppGroup.Installed) }
        fixed.forEach { add(it, labels[it] ?: CommsPresentation.appName(it), CommsAppGroup.Fixed) }
        (installed.filterNot { it.messagingOrSocial } +
            allowed.filterNot { it in seen }.map { CommsAppEntry(it, CommsPresentation.appName(it)) })
            .sortedBy { it.label.lowercase(Locale.ROOT) }
            .forEach { add(it.packageName, it.label, CommsAppGroup.Other) }
        return out
    }
}

/**
 * Pure canned-reply editing for the Settings editor. [CommsCannedReplies.sanitize] stays the single
 * rule for what a stored list may contain, so add and remove can never produce a list the store
 * would reject.
 */
internal object CannedReplyEditor {

    /** Appends [draft] when it survives sanitising; a blank or duplicate draft changes nothing. */
    fun add(current: List<String>, draft: String): List<String> =
        CommsCannedReplies.sanitize(current + draft)

    /** Drops the reply at [index]; an out-of-range index keeps the list as it is. */
    fun remove(current: List<String>, index: Int): List<String> =
        if (index in current.indices) current.filterIndexed { i, _ -> i != index } else current

    /** True when the add control should accept a tap: room left and a new, non-blank reply. */
    fun canAdd(current: List<String>, draft: String): Boolean =
        current.size < CommsCannedReplies.Max &&
            draft.isNotBlank() &&
            draft.trim() !in current
}
