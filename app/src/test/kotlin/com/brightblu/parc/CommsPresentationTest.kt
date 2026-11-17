package com.brightblu.parc

import com.brightblu.parc.core.data.comms.*
import com.brightblu.parc.core.data.notifications.NotificationDrop
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CommsPresentationTest {
    @Test fun actionLabelsUseOneLanguageAndDistinguishDismissScopes() {
        assertEquals("Dismiss all", CommsPresentation.dismissLabel(all = true, terminal = false))
        assertEquals("Dismiss", CommsPresentation.dismissLabel(all = false, terminal = false))
        assertEquals("[x all]", CommsPresentation.dismissLabel(all = true, terminal = true))
        assertEquals("[x]", CommsPresentation.dismissLabel(all = false, terminal = true))
        assertEquals("Quick replies", CommsPresentation.quickRepliesLabel(terminal = false))
        assertEquals("[r]", CommsPresentation.quickRepliesLabel(terminal = true))
        assertEquals("Image", CommsPresentation.imageLabel(terminal = false))
        assertEquals("[img]", CommsPresentation.imageLabel(terminal = true))
    }
    @Test fun cardContainsSenderOnlyInHeaderAndTimeIsLocal() {
        val message = CommsMessage("key", "Sam", "Hey there", 0)
        val c = CommsConversation(CommsConversationId(CommsPackages.WhatsApp, "chat"), "Sam", listOf(message), true, 0)
        assertEquals("WHATSAPP · Sam", CommsPresentation.header(c))
        assertEquals("01:00", CommsPresentation.time(message, ZoneId.of("Europe/Paris")))
        assertEquals("Hey there", CommsPresentation.recent(c).single().text)
    }
    @Test fun cardLineIsTimePlusTextWithoutRepeatingTheSender() {
        val message = CommsMessage("key", "Sam", "Hey there", 0)
        val (clock, body) = CommsPresentation.lineParts(message, ZoneId.of("Europe/Paris"))
        assertEquals("01:00", clock)
        assertEquals("Hey there", body)
        assertFalse(body.contains("Sam"))
        assertFalse(body.contains("whatsapp", ignoreCase = true))
    }

    @Test fun unmappedCommsPackageUsesItsLastSegmentInsteadOfANamedApp() {
        assertEquals("chatty", CommsPresentation.tag("com.example.chatty"))
        assertEquals("verylongpackagen", CommsPresentation.tag("com.example.verylongpackagename"))
    }

    @Test fun threeMeasuredLinesRemainWholeAndMoreRevealsAll() {
        assertNull(CommsPresentation.visibleEnd(listOf(3, 7, 12), false))
        val ends = listOf(3, 7, 12, 19, 23, 29, 31, 38)
        assertEquals(12, CommsPresentation.visibleEnd(ends, false))
        assertNull(CommsPresentation.visibleEnd(ends, true))
    }
    @Test fun newestConversationFirstAndOnlyThreeRecentMessages() {
        fun message(time: Long) = CommsMessage("$time", "sender", "text", time)
        val a = CommsConversation(CommsConversationId("a", "chat"), "chat", (1L..5L).map(::message), true, 5)
        val b = a.copy(id = CommsConversationId("b", "chat"), updatedAtMs = 10)
        assertEquals(listOf(b, a), CommsPresentation.cards(listOf(a, b)))
        assertEquals(listOf(3L, 4L, 5L), CommsPresentation.recent(a).map { it.timeMs })
    }
    @Test fun pageMemoryRestoresAndHiddenOrUnknownFallsBackToDevice() {
        assertEquals("comms", restoredHubPage("comms", legacyHubConfig()))
        assertEquals(DevicePage.Id, restoredHubPage("comms", legacyHubConfig().showPage("comms", false)))
        assertEquals(DevicePage.Id, restoredHubPage("future", HubConfig()))
        assertEquals(TodayPage.Id, restoredHubPage("comms", HubConfig(hiddenPages = HubCatalog.pages.toSet())))
        assertEquals(TodayPage.Id, restoredHubPage("clock", legacyHubConfig()))
    }
    @Test fun selectionMustBeDeliberateAndSurvivesUpdatesButNotDismissal() {
        val id = CommsConversationId("a", "chat")
        val other = CommsConversationId("b", "chat")
        val c = CommsConversation(id, "chat", emptyList(), false, 0)
        assertNull(CommsPresentation.selection(null, listOf(c)))
        assertEquals(id, CommsPresentation.selection(id, listOf(c.copy(updatedAtMs = 10))))
        assertNull(CommsPresentation.selection(other, listOf(c)))
        assertNull(CommsPresentation.selection(id, emptyList()))
    }
    @Test fun replyOutcomesAreActionableAndOldHubConfigGainsSixthPage() {
        assertEquals("sent", CommsPresentation.notice(ReplyResult.Sent))
        assertEquals("no reply button, opens app", CommsPresentation.notice(ReplyResult.NoReplyAction))
        assertEquals("reply failed, opens app", CommsPresentation.notice(ReplyResult.Failed))
        assertEquals("comms", HubConfig.decode("system:true:battery:").pages.last())
        assertEquals(8, legacyHubConfig().pages.size)
    }

    // ---- T213 copy and empty-state support -------------------------------------------------

    @Test fun appNamesAndOpenLabelsAreReadable() {
        assertEquals("WhatsApp", CommsPresentation.appName(CommsPackages.WhatsApp))
        assertEquals("Open Instagram", CommsPresentation.openLabel(CommsPackages.Instagram))
        assertEquals("Chatty", CommsPresentation.appName("com.example.chatty"))
    }

    @Test fun singularPluralAndHeroRules() {
        assertEquals("1 conversation", CommsPresentation.conversationCount(1))
        assertEquals("2 conversations", CommsPresentation.conversationCount(2))
        assertEquals("1 message", CommsPresentation.messageCount(1))
        assertEquals("14 messages", CommsPresentation.messageCount(14))
        assertNull(CommsPresentation.hero(0))
        assertNull(CommsPresentation.hero(1))
        assertEquals(CommsHero("2", "conversations"), CommsPresentation.hero(2))
    }

    @Test fun storedTimesUseTheSameLocalClockAsLiveLines() {
        assertEquals("01:00", CommsPresentation.time(0L, ZoneId.of("Europe/Paris")))
        assertEquals("00:00", CommsPresentation.time(0L, ZoneId.of("UTC")))
    }

    @Test fun footerNamesListeningAccessLiveAndStored() {
        val zone = ZoneId.of("Europe/Paris")
        assertEquals("Listening now", CommsPresentation.listeningSince(null, zone))
        assertEquals("Listening since 01:00", CommsPresentation.listeningSince(0L, zone))
        assertEquals(
            "Listening since 01:00 · access on · 1 live · 3 stored",
            CommsPresentation.footer(0L, accessOn = true, live = 1, stored = 3, zone = zone),
        )
        assertEquals(
            "Listening now · access off · 0 live · 0 stored",
            CommsPresentation.footer(null, accessOn = false, live = 0, stored = 0, zone = zone),
        )
    }

    @Test fun whyRowsArePackageAndReasonOnlyAndAccessOffAddsOneLine() {
        val drops = listOf(
            NotificationDrop("com.whatsapp", CommsDropReason.NotAllowed),
            NotificationDrop("org.telegram.messenger", CommsDropReason.GroupSummary),
        )
        assertEquals(
            listOf(
                CommsWhyRow("com.whatsapp", "not allowed"),
                CommsWhyRow("org.telegram.messenger", "group summary"),
            ),
            CommsPresentation.whyRows(drops, accessOn = true),
        )
        assertEquals(
            listOf("All apps", "com.whatsapp", "org.telegram.messenger"),
            CommsPresentation.whyRows(drops, accessOn = false).map { it.packageName },
        )
        assertEquals("access off", CommsPresentation.whyRows(drops, accessOn = false).first().reason)
        assertEquals(5, CommsPresentation.whyRows((1..7).map {
            NotificationDrop("com.example.$it", CommsDropReason.Blank)
        }, accessOn = true).size)
    }

    @Test fun theCommsTabReadsMessagesWhileTheIdStays() {
        assertEquals("Messages", hubPageName("comms"))
        assertEquals("comms", HubCatalog.pages.first { hubPageName(it) == "Messages" })
        assertEquals("Messages", hubTemplates.getValue("comms").summary)
    }
}
