package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsConversation
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.comms.CommsMessage
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** T233: the compact Messages header, card content rules and the pure layout model. */
class T233CommsTest {

    private fun conversation(
        title: String = "Sam",
        app: String = CommsPackages.WhatsApp,
        messages: List<CommsMessage> = listOf(CommsMessage("k1", "Sam", "Hello", 1_000, canReply = true)),
        canReply: Boolean = true,
    ) = CommsConversation(
        id = CommsConversationId(app, title),
        title = title,
        messages = messages,
        canReply = canReply,
        updatedAtMs = messages.maxOf { it.timeMs },
    )

    private fun pageContentDp(size: HubSize, panelDp: Float): Float =
        HubPanelLayout.heightDp(size, panelDp) - Metrics.cardInsetDp * 2 -
            Metrics.menuRowHeightDp - Metrics.hubGripHeightDp

    private fun rows(count: Int, heightDp: Float) = List(count) { heightDp }

    // ---- header, cards and copy ------------------------------------------------------------

    @Test fun headerIsOneCompactLineWithTheLiveCount() {
        assertEquals("Messages · 2 live", CommsPresentation.headerLine(2))
        assertEquals("Messages · 0 live", CommsPresentation.headerLine(0))
        assertTrue("the header must stay one row", CommsLayout.headerDp <= Metrics.commsActionDp)
        assertTrue(Metrics.commsHeaderTitleSp > Metrics.commsHeaderSp)
    }

    @Test fun aCardUsesTheNewestMessageAndTheActionFollowsCanReply() {
        val messages = listOf(
            CommsMessage("old", "Sam", "First", 1_000, canReply = true),
            CommsMessage("new", "Sam", "Second", 2_000, canReply = true),
        )
        val replying = conversation(messages = messages)
        assertEquals("Second", CommsPresentation.latestText(replying))
        assertEquals("Sam", CommsPresentation.sender(replying))
        assertEquals("Reply", CommsPresentation.liveActionLabel(replying))

        val opening = conversation(messages = messages, canReply = false)
        assertEquals("Open WhatsApp", CommsPresentation.liveActionLabel(opening))
    }

    @Test fun messageTextIsNeverDroppedOrBracketedAndLongTextKeepsItsContent() {
        val long = "A long update with a link https://example.com and enough words to wrap over several lines. ".repeat(4)
        val card = conversation(messages = listOf(CommsMessage("k", "Sam", long, 1_000)))
        assertEquals(long, CommsPresentation.latestText(card))
        assertTrue(!CommsPresentation.latestText(card).contains("["))
        assertEquals(3, Metrics.commsMaxLines)
    }

    @Test fun aSenderFallsBackToTheMessageSenderAndFlattensLineBreaks() {
        val card = conversation(title = "", messages = listOf(CommsMessage("k", "Mira\nVale", "Hi", 1_000)))
        assertEquals("Mira Vale", CommsPresentation.sender(card))
    }

    // ---- History dates and order -----------------------------------------------------------

    private fun stored(id: Long, text: String, date: LocalDate, sender: String = "Sam") =
        CommsHistoryMessage(
            id = id,
            app = CommsPackages.Telegram,
            conversationKey = "Sam",
            conversationTitle = "Sam",
            sender = sender,
            text = text,
            links = emptyList(),
            timeMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + id,
            live = false,
            notificationKey = "",
        )

    @Test fun datesReadTodayYesterdayAndThenTheDay() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 11)
        fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("Today", CommsPresentation.dateLabel(at(today), zone, today))
        assertEquals("Yesterday", CommsPresentation.dateLabel(at(today.minusDays(1)), zone, today))
        assertEquals("3 Nov", CommsPresentation.dateLabel(at(LocalDate.of(2026, 11, 3)), zone, today))
        assertEquals("3 Nov 2025", CommsPresentation.dateLabel(at(LocalDate.of(2025, 11, 3)), zone, today))
    }

    @Test fun historyCardsAreNewestFirstAndSectionsGroupThemByDate() {
        val today = LocalDate.of(2026, 11, 11)
        val yesterday = today.minusDays(1)
        val cards = CommsPresentation.historyCards(listOf(
            CommsHistoryGroup(CommsPackages.Telegram, "Sam", "Sam", listOf(
                stored(1, "older today", today),
                stored(2, "newest today", today),
            )),
            CommsHistoryGroup(CommsPackages.WhatsApp, "Alex", "Alex", listOf(
                stored(3, "yesterday", yesterday),
            )),
        ))
        assertEquals(listOf("newest today", "older today", "yesterday"), cards.map { it.text })
        val sections = CommsPresentation.historySections(cards, ZoneId.of("UTC"), today)
        assertEquals(listOf("Today", "Yesterday"), sections.map { it.label })
        assertEquals(listOf("newest today", "older today"), sections[0].messages.map { it.text })
    }

    // ---- layout model ----------------------------------------------------------------------

    @Test fun rowsVisibleGrowFromCompactToTall() {
        val card = CommsLayout.cardHeightDp(Metrics.commsMaxLines, hasCount = true)
        val compact = pageContentDp(HubSize.Compact, Metrics.hubCompactHeightDp)
        val standard = pageContentDp(HubSize.Standard, 400f)
        val tall = pageContentDp(HubSize.Tall, 600f)

        fun fully(content: Float) = CommsLayout.rowsFullyVisible(
            CommsLayout.cardViewportDp(content), rows(50, card),
        )
        assertTrue("Compact still shows a card slice", CommsLayout.visibleRange(
            CommsLayout.cardViewportDp(compact), rows(50, card)) != null)
        assertEquals(0, fully(compact))
        assertEquals(1, fully(standard))
        assertTrue("Tall shows more than Standard", fully(tall) >= 2)
    }

    @Test fun theLastOfFiftyCardsIsReachableAtEveryHubSize() {
        val card = CommsLayout.cardHeightDp(3, hasCount = true)
        for ((size, panel) in listOf(
            HubSize.Compact to Metrics.hubCompactHeightDp,
            HubSize.Standard to 400f,
            HubSize.Tall to 600f,
        )) {
            val viewport = CommsLayout.cardViewportDp(pageContentDp(size, panel))
            val heights = rows(50, card)
            assertTrue("$size last card clear of the fade",
                CommsLayout.lastCardReachable(viewport, heights))
            val content = CommsLayout.contentHeightDp(heights)
            val atEnd = CommsLayout.visibleRange(viewport, heights, scrollOffsetDp = content - viewport)
            assertNotNull("$size has a visible tail", atEnd)
            assertEquals("$size reaches the last card", 49, atEnd!!.last)
        }
    }

    @Test fun longTextIsCappedAtThreeLines() {
        val one = CommsLayout.cardHeightDp(1)
        val three = CommsLayout.cardHeightDp(3)
        assertEquals(three, CommsLayout.cardHeightDp(8), 0f)
        assertTrue(three > one)
    }

    @Test fun aLargeFontScaleGrowsCardsAndStillReachesTheLastCard() {
        val normal = CommsLayout.cardHeightDp(3, fontScale = 1f)
        val large = CommsLayout.cardHeightDp(3, fontScale = 2f)
        assertTrue(large > normal)
        val viewport = CommsLayout.cardViewportDp(pageContentDp(HubSize.Standard, 400f))
        val heights = rows(50, large)
        assertTrue(CommsLayout.lastCardReachable(viewport, heights))
        val content = CommsLayout.contentHeightDp(heights)
        assertEquals(49, CommsLayout.visibleRange(viewport, heights, scrollOffsetDp = content - viewport)!!.last)
    }

    @Test fun emptyContentHasNoRowsAndNoNegativeViewport() {
        assertEquals(0f, CommsLayout.contentHeightDp(emptyList()), 0f)
        assertEquals(0f, CommsLayout.cardViewportDp(4f), 0f)
        assertEquals(0, CommsLayout.rowsFullyVisible(400f, emptyList()))
        assertEquals(null, CommsLayout.visibleRange(400f, emptyList()))
    }
}
