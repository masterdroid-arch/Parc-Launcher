package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsCannedReplies
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.comms.CommsHistoryEntry
import com.brightblu.parc.core.data.comms.CommsMessage
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.core.data.comms.MarkReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T77: the pure page and card state rules behind the Comms extras UI. */
class CommsExtrasTest {

    private fun entry(app: String, title: String, text: String, timeMs: Long, removedAtMs: Long = timeMs,
        key: String = title) =
        CommsHistoryEntry(CommsConversationId(app, key), title,
            CommsMessage("key-$timeMs", "sender", text, timeMs), removedAtMs)

    // ---- page mode -------------------------------------------------------------------------

    @Test fun commsOffAlwaysShowsTheEmptyState() {
        // The history is cleared when Comms is switched off, so an off page never shows it.
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(false, false, 0, 0))
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(false, true, 3, 5))
    }

    @Test fun historyToggleWinsOverLiveCardsAndNamesItsEmptyState() {
        assertEquals(CommsMode.Live, CommsExtrasPresentation.mode(true, false, 2, 4))
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(true, false, 0, 4))
        assertEquals(CommsMode.History, CommsExtrasPresentation.mode(true, true, 2, 4))
        assertEquals(CommsMode.NoHistory, CommsExtrasPresentation.mode(true, true, 2, 0))
        assertEquals(CommsMode.NoHistory, CommsExtrasPresentation.mode(true, true, 0, 0))
    }

    @Test fun historyToggleAndLabelShowTheState() {
        assertEquals("[history]", CommsExtrasPresentation.historyToggle(false))
        assertEquals("[history on]", CommsExtrasPresentation.historyToggle(true))
        assertEquals("history", CommsExtrasPresentation.historyLabel(0))
        assertEquals("history 7", CommsExtrasPresentation.historyLabel(7))
    }

    // ---- card state ------------------------------------------------------------------------

    @Test fun anUnselectedCardCarriesNoReplyControls() {
        val state = CommsExtrasPresentation.cardState(false, CommsCannedReplies.Default)
        assertFalse(state.showField)
        assertFalse(state.showQuickReplies)
    }

    @Test fun theSelectedCardShowsTheFieldAndTheChipsInStoredOrder() {
        val state = CommsExtrasPresentation.cardState(true, listOf("On my way", "OK"))
        assertTrue(state.selected)
        assertTrue(state.showField)
        assertTrue(state.showQuickReplies)
        assertEquals(listOf("On my way", "OK"), state.quickReplies)
    }

    @Test fun aSelectedCardWithoutCannedRepliesKeepsOnlyTheField() {
        val state = CommsExtrasPresentation.cardState(true, emptyList())
        assertTrue(state.showField)
        assertFalse(state.showQuickReplies)
        assertTrue(state.quickReplies.isEmpty())
    }

    // ---- history view ----------------------------------------------------------------------

    @Test fun historyGroupsByConversationInFirstAppearanceOrderNewestRemovalFirst() {
        val entries = listOf(
            entry(CommsPackages.Telegram, "Sam", "one", 10, 100),
            entry(CommsPackages.WhatsApp, "Alex", "two", 20, 90),
            entry(CommsPackages.Telegram, "Sam", "three", 30, 80),
        )
        val cards = CommsExtrasPresentation.historyCards(entries)
        assertEquals(listOf("TELEGRAM · Sam", "WHATSAPP · Alex"), cards.map { it.header })
        // Inside a card the lines read oldest first, like an active card.
        assertEquals(listOf("three", "one"), cards[0].lines.map { it.message.text })
        assertEquals(listOf("two"), cards[1].lines.map { it.message.text })
    }

    @Test fun historyHeaderUsesTheMostRecentTitleAndCollapsesLineBreaks() {
        val entries = listOf(
            entry(CommsPackages.Signal, "New name\nline", "newest", 20, 90, key = "chat"),
            entry(CommsPackages.Signal, "Old name", "older", 10, 80, key = "chat"),
        )
        assertEquals("SIGNAL · New name line", CommsExtrasPresentation.historyCards(entries).single().header)
    }

    @Test fun emptyHistoryHasNoCards() {
        assertTrue(CommsExtrasPresentation.historyCards(emptyList()).isEmpty())
    }

    @Test fun everyReadOutcomeIsNamed() {
        assertEquals("read", CommsExtrasPresentation.readNotice(MarkReadResult.Marked))
        assertEquals("read, notifications cleared", CommsExtrasPresentation.readNotice(MarkReadResult.Dismissed))
        assertEquals("nothing to read", CommsExtrasPresentation.readNotice(MarkReadResult.Unavailable))
        assertEquals("read failed", CommsExtrasPresentation.readNotice(MarkReadResult.Failed))
    }

    @Test fun aNoticeStaysOnItsCardUntilTheCardLeaves() {
        assertEquals(CommsNoticePlacement.None, CommsExtrasPresentation.noticePlacement(null, emptyList()))
        assertEquals(CommsNoticePlacement.Card,
            CommsExtrasPresentation.noticePlacement(CommsPackages.Telegram, listOf(CommsPackages.Telegram)))
        assertEquals(CommsNoticePlacement.Page,
            CommsExtrasPresentation.noticePlacement(CommsPackages.Telegram, listOf(CommsPackages.Signal)))
        assertEquals(CommsNoticePlacement.Page, CommsExtrasPresentation.noticePlacement(CommsPackages.Telegram, emptyList()))
    }

    // ---- canned reply editor ---------------------------------------------------------------

    @Test fun addingARepliesAppendsAndSanitises() {
        val current = listOf("Thanks")
        assertEquals(listOf("Thanks", "On my way"), CannedReplyEditor.add(current, " On my way "))
        assertEquals(listOf("Thanks"), CannedReplyEditor.add(current, "   "))
        assertEquals(listOf("Thanks"), CannedReplyEditor.add(current, "Thanks"))
        assertEquals(listOf("Thanks", "two words"), CannedReplyEditor.add(current, "two\nwords"))
    }

    @Test fun addingStopsAtEight() {
        val full = (1..CommsCannedReplies.Max).map { "reply $it" }
        assertEquals(full, CannedReplyEditor.add(full, "one more"))
        assertEquals(CommsCannedReplies.Max, CannedReplyEditor.add(full.take(7), "one more").size)
    }

    @Test fun removingDropsOnlyTheTappedIndex() {
        val current = listOf("a", "b", "c")
        assertEquals(listOf("b", "c"), CannedReplyEditor.remove(current, 0))
        assertEquals(listOf("a", "c"), CannedReplyEditor.remove(current, 1))
        assertEquals(current, CannedReplyEditor.remove(current, 3))
        assertEquals(current, CannedReplyEditor.remove(current, -1))
    }

    @Test fun addIsOfferedOnlyWithRoomAndANewNonBlankReply() {
        val current = listOf("Thanks")
        assertTrue(CannedReplyEditor.canAdd(current, "OK"))
        assertFalse(CannedReplyEditor.canAdd(current, "  "))
        assertFalse(CannedReplyEditor.canAdd(current, " Thanks "))
        assertFalse(CannedReplyEditor.canAdd((1..CommsCannedReplies.Max).map { "r$it" }, "OK"))
    }
}
