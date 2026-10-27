package com.brightblu.parc

import com.brightblu.parc.core.data.comms.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class CommsPresentationTest {
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
    @Test fun pageMemoryRestoresAndHiddenOrUnknownFallsBackToSystem() {
        assertEquals("comms", restoredHubPage("comms", HubConfig()))
        assertEquals("system", restoredHubPage("comms", HubConfig().showPage("comms", false)))
        assertEquals("system", restoredHubPage("future", HubConfig()))
        assertEquals("system", restoredHubPage("comms", HubConfig(hiddenPages = HubCatalog.pages.toSet())))
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
        assertEquals(11, HubConfig().pages.size)
    }
}
