package com.brightblu.parc

import com.brightblu.parc.core.data.comms.CommsConversation
import com.brightblu.parc.core.data.comms.CommsConversationId
import com.brightblu.parc.core.data.comms.CommsMessage
import com.brightblu.parc.core.data.comms.CommsMessageEntity
import com.brightblu.parc.core.data.comms.CommsPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T215: pure History grouping, search, paging, storage and reply/open selection. */
class CommsHistoryUiTest {

    private fun row(
        id: Long,
        text: String,
        timeMs: Long,
        app: String = CommsPackages.Telegram,
        conversation: String = "Sam",
        title: String = "Sam",
        sender: String = "Sam",
        live: Boolean = false,
        key: String = "",
        links: String = "[]",
    ) = CommsMessageEntity(
        id = id,
        app = app,
        conversationKey = conversation,
        conversationTitle = title,
        sender = sender,
        notificationTitle = title,
        text = text,
        links = links,
        timeMs = timeMs,
        recordedAtMs = timeMs,
        notificationKey = key,
        identity = "identity-$id",
        live = live,
    )

    // ---- grouping, order and search -------------------------------------------------------

    @Test fun groupsFollowTheNewestMessageAndMessagesInsideReadNewestFirst() {
        val groups = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "one", 100),
            row(2, "two", 200, app = CommsPackages.WhatsApp, conversation = "Alex", title = "Alex"),
            row(3, "three", 300),
        ))
        assertEquals(listOf("TELEGRAM · Sam", "WHATSAPP · Alex"), groups.map {
            CommsPresentation.header(it.app, it.title)
        })
        assertEquals(listOf("three", "one"), groups[0].messages.map { it.text })
        assertEquals(listOf("two"), groups[1].messages.map { it.text })
        assertEquals(3, CommsExtrasPresentation.historyTotal(groups))
    }

    @Test fun unsortedInputStillReadsNewestFirstAndCollapsesTitleLineBreaks() {
        val groups = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "older", 10, title = "Old name"),
            row(2, "newest", 20, title = "New name\nline"),
        )).single()
        assertEquals("TELEGRAM · New name line", CommsPresentation.header(groups.app, groups.title))
        assertEquals(listOf("newest", "older"), groups.messages.map { it.text })
    }

    @Test fun searchMatchesTextSenderOrConversationTitleIgnoringCase() {
        val rows = listOf(
            row(1, "Meet at six", 100, sender = "Sam", title = "Sam"),
            row(2, "Invoice", 200, app = CommsPackages.WhatsApp, conversation = "Work",
                title = "Work chat", sender = "Alex"),
        )
        assertEquals(listOf("Meet at six"),
            CommsExtrasPresentation.historyGroups(rows, "SIX").flatMap { it.messages }.map { it.text })
        assertEquals(listOf("Invoice"),
            CommsExtrasPresentation.historyGroups(rows, "alex").flatMap { it.messages }.map { it.text })
        assertEquals(listOf("Invoice"),
            CommsExtrasPresentation.historyGroups(rows, "work chat").flatMap { it.messages }.map { it.text })
        assertTrue(CommsExtrasPresentation.historyGroups(rows, "nothing here").isEmpty())
    }

    // ---- paging ----------------------------------------------------------------------------

    @Test fun firstPageHoldsTwoHundredRowsAndShowOlderOnlyAddsMore() {
        val rows = (1..450).map { row(it.toLong(), "message $it", it.toLong()) }
        val all = CommsExtrasPresentation.historyGroups(rows)
        val first = CommsExtrasPresentation.historyPage(all, CommsExtrasPresentation.HistoryPageSize)
        val second = CommsExtrasPresentation.historyPage(all, CommsExtrasPresentation.HistoryPageSize * 2)

        assertEquals(200, CommsExtrasPresentation.historyTotal(first))
        assertEquals(400, CommsExtrasPresentation.historyTotal(second))
        assertEquals(450, CommsExtrasPresentation.historyTotal(all))
        // Paging never drops a row from the source list.
        assertEquals(450, all.sumOf { it.messages.size })
    }

    @Test fun pagingSplitsAGroupWithoutLosingItsNewestRows() {
        val groups = CommsExtrasPresentation.historyGroups((1..5).map {
            row(it.toLong(), "message $it", it.toLong())
        })
        val page = CommsExtrasPresentation.historyPage(groups, 3)
        assertEquals(listOf("message 5", "message 4", "message 3"), page.single().messages.map { it.text })
    }

    @Test fun aThousandOldRowsAreStillCounted() {
        val rows = (1..1_000).map { row(it.toLong(), "message $it", 0L) }
        assertEquals(1_000, CommsExtrasPresentation.historyTotal(CommsExtrasPresentation.historyGroups(rows)))
    }

    // ---- storage line ----------------------------------------------------------------------

    @Test fun storageLineAlwaysCarriesAnExplicitPluralisedCountAndWholeKilobytes() {
        assertEquals("1 message · 1 KB", CommsExtrasPresentation.storageLine(1, 900))
        assertEquals("312 messages · 180 KB", CommsExtrasPresentation.storageLine(312, 180L * 1024L))
        assertEquals("0 messages · 0 KB", CommsExtrasPresentation.storageLine(0, 0))
        assertEquals("2 messages · 2 KB", CommsExtrasPresentation.storageLine(2, 1025))
        assertEquals("0 KB", CommsExtrasPresentation.storageSize(0))
    }

    @Test fun storageBytesComeFromStoredTextAndLinksOnly() {
        val row = row(1, "hello", 1_000, links = "[[0,5]]")
        val bytes = CommsExtrasPresentation.storageBytes(listOf(row))
        assertTrue(bytes >= "hello".length + 64)
        assertTrue(bytes >= "[[0,5]]".length)
    }

    // ---- live vs stale, reply vs open ------------------------------------------------------

    private fun conversation(key: String, canReply: Boolean) = CommsConversation(
        id = CommsConversationId(CommsPackages.Telegram, "Sam"),
        title = "Sam",
        messages = listOf(CommsMessage(key, "Sam", "hello", 1_000, canReply = canReply)),
        canReply = canReply,
        updatedAtMs = 1_000,
    )

    @Test fun aLiveNotificationThatCanReplyOffersReply() {
        val message = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "hello", 1_000, live = true, key = "n1"),
        )).single().messages.single()
        val state = CommsExtrasPresentation.historyAction(message, listOf(conversation("n1", canReply = true)))
        assertEquals(CommsHistoryAction.Reply, state.action)
        assertNull(state.status)
    }

    @Test fun aLiveNotificationWithoutAReplyActionOpensTheAppAndSaysWhy() {
        val message = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "hello", 1_000, live = true, key = "n1"),
        )).single().messages.single()
        val state = CommsExtrasPresentation.historyAction(message, listOf(conversation("n1", canReply = false)))
        assertEquals(CommsHistoryAction.Open, state.action)
        assertEquals("This app does not allow replies here.", state.status)
    }

    @Test fun aStoredRowWhoseNotificationIsGoneOnlyOpensTheApp() {
        val stored = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "hello", 1_000, live = false, key = ""),
        )).single().messages.single()
        val gone = CommsExtrasPresentation.historyAction(stored, emptyList())
        assertEquals(CommsHistoryAction.Open, gone.action)
        assertEquals("notification is gone", gone.status)

        val liveFlagButMissingKey = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "hello", 1_000, live = true, key = "n1"),
        )).single().messages.single()
        assertEquals("notification is gone",
            CommsExtrasPresentation.historyAction(liveFlagButMissingKey, listOf(conversation("other", true))).status)
    }

    // ---- text and links only ---------------------------------------------------------------

    @Test fun imageOnlyRowsNeverEnterTheHistoryModelAndCaptionsDo() {
        val groups = CommsExtrasPresentation.historyGroups(listOf(
            row(1, "", 100),
            row(2, "caption only", 200),
            row(3, "caption with link", 300, links = "[[0,7]]"),
        ))
        assertEquals(listOf("caption with link", "caption only"), groups.flatMap { it.messages }.map { it.text })
    }

    @Test fun theHistoryModelHasNoImageField() {
        assertFalse(CommsHistoryMessage::class.java.declaredFields.any { it.name.contains("image", ignoreCase = true) })
    }
}
