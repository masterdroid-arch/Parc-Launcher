package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsCannedReplies
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.core.data.comms.MarkReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T77/T215: the pure page, card and segment rules behind the Messages UI. */
class CommsExtrasTest {

    // ---- page mode -------------------------------------------------------------------------

    @Test fun liveSegmentFallsBackToTheEmptyStateWhenOffOrAccessIsGone() {
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(CommsSegment.Live, false, true, 0, 5))
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(CommsSegment.Live, true, false, 3, 5))
        assertEquals(CommsMode.Live, CommsExtrasPresentation.mode(CommsSegment.Live, true, true, 2, 4))
        assertEquals(CommsMode.Empty, CommsExtrasPresentation.mode(CommsSegment.Live, true, true, 0, 4))
    }

    @Test fun historySegmentNeverDependsOnAccessOrTheLiveSwitch() {
        assertEquals(CommsMode.History, CommsExtrasPresentation.mode(CommsSegment.History, false, false, 0, 5))
        assertEquals(CommsMode.History, CommsExtrasPresentation.mode(CommsSegment.History, true, true, 2, 4))
        assertEquals(CommsMode.NoHistory, CommsExtrasPresentation.mode(CommsSegment.History, false, true, 2, 0))
        assertEquals(CommsMode.NoHistory, CommsExtrasPresentation.mode(CommsSegment.History, true, true, 0, 0))
    }

    @Test fun segmentLabelsArePlainWordsUnlessTheSkinIsTerminal() {
        assertEquals("Live", CommsExtrasPresentation.segmentLabel(CommsSegment.Live))
        assertEquals("History", CommsExtrasPresentation.segmentLabel(CommsSegment.History))
        assertFalse(CommsExtrasPresentation.segmentLabel(CommsSegment.History).contains("["))
        assertEquals("[history]", CommsExtrasPresentation.segmentLabel(CommsSegment.History, terminal = true))
        assertEquals("[live]", CommsExtrasPresentation.segmentLabel(CommsSegment.Live, terminal = true))
        assertEquals(CommsSegment.Live, CommsExtrasPresentation.defaultSegment(accessOn = true))
        assertEquals(CommsSegment.History, CommsExtrasPresentation.defaultSegment(accessOn = false))
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
        assertTrue(state.canReply)
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

    @Test fun aNonReplyableCardNeverShowsADeadFieldOrChips() {
        val state = CommsExtrasPresentation.cardState(true, listOf("On my way"), canReply = false)
        assertTrue(state.selected)
        assertFalse(state.canReply)
        assertFalse(state.showField)
        assertFalse(state.showQuickReplies)
        // An unselected non-replyable card is still just unselected.
        assertFalse(CommsExtrasPresentation.cardState(false, listOf("OK"), canReply = false).showField)
    }

    // ---- empty reasons ---------------------------------------------------------------------

    @Test fun accessOffWinsAndCarriesTheHistoryNote() {
        val state = requireNotNull(
            CommsExtrasPresentation.emptyState(
                accessOn = false, enabled = true, live = 2, stored = 3,
                focusMode = "Work", focusHiddenApps = listOf(CommsPackages.WhatsApp), drops = 2,
            ),
        )
        assertEquals(CommsEmptyReason.AccessOff, state.reason)
        assertEquals("Notification access is off", state.message)
        assertEquals(CommsEmptyAction.Allow, state.action)
        assertNotNull(state.note)
        assertTrue(state.note!!.contains("3"))
        assertTrue(state.note!!.contains("stored"))
    }

    @Test fun accessOffWithoutHistoryHasNoNote() {
        assertNull(CommsExtrasPresentation.emptyState(false, enabled = true, live = 0, stored = 0)?.note)
    }

    @Test fun messagesOffOffersTurnOn() {
        val state = requireNotNull(CommsExtrasPresentation.emptyState(accessOn = true, enabled = false, live = 0, stored = 0))
        assertEquals(CommsEmptyReason.MessagesOff, state.reason)
        assertEquals("Messages are off", state.message)
        assertEquals(CommsEmptyAction.TurnOn, state.action)
        assertEquals("Turn on", state.actionLabel)
    }

    @Test fun focusHiddenNamesTheModeAndTheApp() {
        val state = requireNotNull(
            CommsExtrasPresentation.emptyState(
                accessOn = true, enabled = true, live = 0, stored = 0,
                focusMode = "Work", focusHiddenApps = listOf(CommsPackages.WhatsApp),
            ),
        )
        assertEquals(CommsEmptyReason.FocusHidden, state.reason)
        assertEquals("Focus mode Work is hiding WhatsApp", state.message)
        assertEquals(CommsEmptyAction.OpenFocus, state.action)
    }

    @Test fun noLiveMessagesOfferHistoryOnlyWhenSomethingIsStored() {
        val withHistory = requireNotNull(
            CommsExtrasPresentation.emptyState(true, enabled = true, live = 0, stored = 2),
        )
        assertEquals(CommsEmptyReason.NoLive, withHistory.reason)
        assertEquals(CommsEmptyAction.OpenHistory, withHistory.action)
        assertEquals("No live messages. History shows what was kept on this phone.", withHistory.message)

        val withNoHistory = requireNotNull(
            CommsExtrasPresentation.emptyState(true, enabled = true, live = 0, stored = 0),
        )
        assertEquals(CommsEmptyReason.NoLive, withNoHistory.reason)
        assertEquals(CommsEmptyAction.Why, withNoHistory.action)
    }

    @Test fun filteredDropsExplainWhyNothingArrived() {
        val state = requireNotNull(
            CommsExtrasPresentation.emptyState(true, enabled = true, live = 0, stored = 0, drops = 3),
        )
        assertEquals(CommsEmptyReason.AllFiltered, state.reason)
        assertEquals("Nothing has arrived since Parc started listening.", state.message)
        assertEquals(CommsEmptyAction.Why, state.action)
        assertEquals("Why?", state.actionLabel)
    }

    @Test fun liveCardsHaveNoEmptyState() {
        assertNull(CommsExtrasPresentation.emptyState(true, enabled = true, live = 1, stored = 4))
    }

    // ---- apps picker -----------------------------------------------------------------------

    private fun app(pkg: String, label: String, messaging: Boolean = false) = CommsAppEntry(pkg, label, messaging)

    @Test fun pickerGroupsInstalledMessagingThenFixedThenOther() {
        val choices = CommsAppPicker.choices(
            installed = listOf(
                app("com.z.other", "Zed"),
                app(CommsPackages.WhatsApp, "WhatsApp", messaging = true),
                app("com.a.other", "Alpha"),
            ),
            allowed = setOf("com.example.stored"),
        )
        assertEquals(
            listOf(CommsPackages.WhatsApp),
            choices.filter { it.group == CommsAppGroup.Installed }.map { it.packageName },
        )
        assertEquals(CommsPackages.FixedOptIns, choices.filter { it.group == CommsAppGroup.Fixed }.map { it.packageName })
        assertEquals(
            listOf("com.a.other", "com.example.stored", "com.z.other"),
            choices.filter { it.group == CommsAppGroup.Other }.map { it.packageName },
        )
        assertEquals(
            listOf(CommsAppGroup.Installed, CommsAppGroup.Fixed, CommsAppGroup.Other),
            choices.map { it.group }.distinct(),
        )
    }

    @Test fun theFixedAppsAreNotDuplicatedWhenTheyAreInstalled() {
        val choices = CommsAppPicker.choices(
            installed = listOf(
                app(CommsPackages.Telegram, "Telegram", messaging = true),
                app(CommsPackages.Signal, "Signal"),
            ),
        )
        assertEquals(listOf(CommsPackages.Telegram), choices.filter { it.group == CommsAppGroup.Installed }.map { it.packageName })
        assertEquals(
            listOf(CommsPackages.Messages, CommsPackages.Signal, CommsPackages.Discord, CommsPackages.Imo),
            choices.filter { it.group == CommsAppGroup.Fixed }.map { it.packageName },
        )
        assertEquals(listOf(CommsPackages.Telegram, CommsPackages.Messages, CommsPackages.Signal,
            CommsPackages.Discord, CommsPackages.Imo), choices.map { it.packageName })
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
