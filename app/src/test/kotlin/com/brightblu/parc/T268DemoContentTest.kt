package com.brightblu.parc

import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderHighlightTone
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.reader.ReaderDemo
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T268: the demo-only content and the pure figures behind it. */
class T268DemoContentTest {
    private val today = LocalDate.of(2026, 11, 17)

    @Test
    fun theDemoHubIncludesDeviceControlsAndScreenTime() {
        assertTrue(DevicePage.Id in DemoHubPages)
        assertTrue(ControlsPage.Id in DemoHubPages)
        assertTrue("apps" in DemoHubPages)
        assertTrue(DemoHubPages.indexOf(DevicePage.Id) > DemoHubPages.indexOf(TodayPage.Id))
        assertTrue(DemoHubPages.indexOf("apps") > DemoHubPages.indexOf(DevicePage.Id))
    }

    @Test
    fun usageFiguresFollowThePeriodAndUseTheHonestLabels() {
        val day = DemoUsage.network(LedgerPeriod.Today, today)
        val week = DemoUsage.network(LedgerPeriod.Week, today)
        val month = DemoUsage.network(LedgerPeriod.Month, today)
        val all = DemoUsage.network(LedgerPeriod.All, today)
        assertEquals(1, day.summary.days)
        assertEquals(7, week.summary.days)
        assertEquals(7, week.summary.networkDays)
        assertEquals(30, month.summary.days)
        assertEquals(day.summary.wifiBytes * 7, week.summary.wifiBytes)
        assertEquals(day.summary.mobileBytes * 7, week.summary.mobileBytes)
        assertEquals(day.summary.screenOnMillis * 7, week.summary.screenOnMillis)
        assertEquals("7 days", week.summary.networkLabel())
        assertEquals("30 days", month.summary.screenLabel())
        assertTrue(all.summary.networkLabel().startsWith("All time · since "))
        assertTrue(all.summary.days >= 180)
        assertTrue(all.summary.wifiBytes > month.summary.wifiBytes)
    }

    @Test
    fun usageAppsScaleWithTheSamePeriod() {
        val day = DemoUsage.screen(LedgerPeriod.Today, today)
        val week = DemoUsage.screen(LedgerPeriod.Week, today)
        assertEquals(3, day.apps.size)
        assertEquals(day.apps.map { it.label }, week.apps.map { it.label })
        assertEquals(day.apps.map { it.value * 7 }, week.apps.map { it.value })
        assertTrue(day.apps.all { it.value > 0 })
    }

    @Test
    fun everyDemoLibraryBookUsesTheBuiltInPublicDomainSample() {
        assertTrue(DemoLibraryBooks.isNotEmpty())
        val ordered = LibraryFormat.order(DemoLibraryBooks)
        assertTrue(ReaderDemo.isDemoBook(ordered.first()))
        assertEquals("Continue", LibraryFormat.secondaryLine(ordered.first(), isContinue = true, missing = false))
        DemoLibraryBooks.forEach { book ->
            assertTrue(ReaderDemo.isDemoBook(book))
            val doc = ReaderDemo.doc(book)
            assertEquals(book.id, doc.id)
            assertEquals(book.title, doc.title)
            assertEquals(1, doc.chapters.size)
            assertTrue(doc.chapters.first().text.contains("public domain"))
            assertTrue(doc.chapters.first().text.contains("The Wonderful Wizard of Oz"))
        }
    }

    @Test
    fun theReaderShowsOneDemoHighlightAndKeepsStoredMarks() {
        val marks = ReaderDemo.marks()
        assertEquals(1, marks.highlights.size)
        val demo = marks.highlights.single()
        assertEquals(ReaderDemo.HighlightId, demo.id)
        assertEquals(ReaderHighlightTone.Sand, demo.tone)
        assertEquals(ReaderDemo.HighlightPhrase, ReaderDemo.SampleText.substring(demo.start, demo.end))
        assertTrue(demo.end <= ReaderDemo.SampleText.length)

        val stored = ReaderBookMarks(highlights = listOf(
            ReaderHighlight("kept", 0, 0, demo.start, ReaderHighlightTone.Sage, "Intro"),
        ))
        val merged = ReaderDemo.withHighlight(stored)
        assertEquals(2, merged.highlights.size)
        assertEquals(1, merged.highlights.count { it.id == ReaderDemo.HighlightId })
        assertEquals("kept", merged.highlights.last().id)
        assertEquals(merged, ReaderDemo.withHighlight(merged))
    }

    @Test
    fun demoTodayUsesTheLiveDateAndDemoLibraryOpensTheReader() {
        val source = File("src/main/kotlin/com/brightblu/parc/DemoHubPages.kt").readText()
        assertTrue(source.contains("LocalDate.now()"))
        assertFalse(source.contains("LocalDate.of(2026, 11, 10)"))
        val library = File("src/main/kotlin/com/brightblu/parc/LibraryPage.kt").readText()
        assertTrue(library.contains("onOpen = { book, cover -> ReaderContract.open(context, book, cover) }"))
        assertTrue(library.contains("DemoFindBooksDialog"))
        assertTrue(library.contains("if (DemoMode.enabled) DemoFindBooksDialog"))
        val files = File("src/main/kotlin/com/brightblu/parc/FilesSearchMode.kt").readText()
        assertTrue(files.contains("if (DemoMode.enabled)"))
        assertTrue(files.contains("DemoFindBooksDialog"))
    }
}
