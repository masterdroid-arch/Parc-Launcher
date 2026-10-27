package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsCannedReplies
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.comms.CommsHistoryEntry
import com.brightblu.parc.core.data.comms.MarkReadResult

/**
 * What the Comms page is showing (T77). [History] and [NoHistory] are the dismissed-message view,
 * which exists only while the `history` toggle is on; [Empty] is Comms off or no active message.
 * Pure so the whole page state is testable without Compose.
 */
internal enum class CommsMode { Live, Empty, History, NoHistory }

/**
 * The reply controls a single card carries. The field and the chips follow the selection (the card
 * the user opened); chips exist only when the canned list has entries, so an install with every
 * reply removed keeps today's card. Read and dismiss-all are always offered on a live card.
 */
internal data class CommsCardState(
    val selected: Boolean,
    val showField: Boolean,
    val showQuickReplies: Boolean,
    val quickReplies: List<String>,
)

/** One dimmed block of the history view: a conversation and the messages dismissed from it. */
internal data class CommsHistoryCard(
    val header: String,
    val lines: List<CommsHistoryEntry>,
)

/** Where a live status line is drawn (T77). [Page] covers a card that just left the list. */
internal enum class CommsNoticePlacement { None, Card, Page }

/**
 * T77 presentation rules. Everything here is pure: no Android, no Compose, no allocation in a draw
 * loop (the UI calls these during composition, never while drawing).
 */
internal object CommsExtrasPresentation {

    /**
     * The page mode. Comms off wins over everything (the history is cleared when it is switched
     * off). With the toggle on, an empty history says so instead of falling back to live cards,
     * because the user asked for the dismissed view.
     */
    fun mode(enabled: Boolean, historyOn: Boolean, conversations: Int, dismissed: Int): CommsMode = when {
        !enabled -> CommsMode.Empty
        historyOn && dismissed > 0 -> CommsMode.History
        historyOn -> CommsMode.NoHistory
        conversations > 0 -> CommsMode.Live
        else -> CommsMode.Empty
    }

    /** The card's reply controls for the current selection and canned list. */
    fun cardState(selected: Boolean, cannedReplies: List<String>): CommsCardState = CommsCardState(
        selected = selected,
        showField = selected,
        showQuickReplies = selected && cannedReplies.isNotEmpty(),
        quickReplies = cannedReplies,
    )

    /** `[history]` opens the dismissed view; `[history on]` is the toggle in its on state. */
    fun historyToggle(historyOn: Boolean): String = if (historyOn) "[history on]" else "[history]"

    /** The `history` line before the toggle; the count is the number of dismissed messages. */
    fun historyLabel(dismissed: Int): String = if (dismissed > 0) "history $dismissed" else "history"

    /**
     * Groups the newest-removal-first [entries] by conversation, keeping each conversation's first
     * appearance as the card order. Inside a card the lines are oldest first, like an active card.
     * The header comes from the most recently dismissed line, which is the freshest title.
     */
    fun historyCards(entries: List<CommsHistoryEntry>): List<CommsHistoryCard> {
        if (entries.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<CommsConversationId, MutableList<CommsHistoryEntry>>()
        for (entry in entries) {
            grouped.getOrPut(entry.conversation) { ArrayList() }.add(entry)
        }
        return grouped.map { (id, lines) ->
            CommsHistoryCard(
                header = CommsPresentation.header(id.app, lines.first().title),
                lines = lines.asReversed(),
            )
        }
    }

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
