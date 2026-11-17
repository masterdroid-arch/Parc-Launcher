package com.brightblu.parc

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class QuoteRulesTest {
    private val day = LocalDate.of(2026, 11, 10)
    private val ids = listOf("a", "b", "c")
    @Test fun dailyPickIsStableAndEachCycleShowsEveryVisibleQuoteOnce() {
        var order = QuoteOrder()
        val picked = (0..8).map { offset ->
            order = QuoteRules.pick(order, ids, day.plusDays(offset.toLong()))
            assertEquals(order, QuoteRules.pick(order, ids, day.plusDays(offset.toLong())))
            order.current
        }
        picked.chunked(3).forEach { assertEquals(ids.toSet(), it.toSet()) }
        picked.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }
    @Test fun tappingAdvancesTheSavedPickEvenOnTheSameDay() {
        val first = QuoteRules.pick(QuoteOrder(), ids, day)
        val second = QuoteRules.pick(first, ids, day, another = true)
        assertNotEquals(first.current, second.current)
        assertEquals(second, QuoteRules.pick(second, ids, day))
        assertEquals("c", QuoteRules.pick(second, ids, day.plusDays(1)).current)
    }
    @Test fun addingMidCycleJoinsTheUnshownTailBeforeAnyRepeat() {
        var order = QuoteRules.pick(QuoteOrder(), ids, day)
        order = QuoteRules.pick(order, ids + "d", day)
        assertEquals("a", order.current)
        assertEquals(listOf("b", "c", "d"), order.remaining)
        val picked = (1..3).map { order = QuoteRules.pick(order, ids + "d", day, true); order.current }
        assertEquals(listOf("b", "c", "d"), picked)
    }
    @Test fun deletingTheCurrentOrAnUnseenQuoteNeverReturnsADeletedId() {
        val first = QuoteRules.pick(QuoteOrder(), ids, day)
        val next = QuoteRules.pick(first, listOf("b", "c"), day)
        assertEquals("b", next.current)
        assertEquals(setOf("b"), next.shown)
        val noB = QuoteRules.pick(first, listOf("a", "c"), day, true)
        assertEquals("c", noB.current)
        assertFalse("b" in noB.remaining)
    }
    @Test fun allHiddenAndSingleQuoteAreSafeAndAddingToEmptyStartsImmediately() {
        val empty = QuoteRules.pick(QuoteOrder(current = "a"), emptyList(), day)
        assertNull(empty.current)
        assertTrue(empty.remaining.isEmpty())
        val one = QuoteRules.pick(empty, listOf("only"), day)
        assertEquals("only", one.current)
        assertEquals(one, QuoteRules.pick(one, listOf("only"), day, true))
        assertEquals("only", QuoteRules.pick(one, listOf("only"), day.plusDays(1)).current)
    }
    @Test fun cycleBoundaryDoesNotImmediatelyRepeatTheLastQuote() {
        val order = QuoteOrder(day.toString(), "a", shown = ids.toSet())
        assertEquals("b", QuoteRules.pick(order, ids, day, true).current)
    }
    @Test fun localMidnightAndZoneChangesUseTheCallersLocalDate() {
        val now = Instant.parse("2026-11-10T23:30:00Z")
        val lisbon = now.atZone(ZoneId.of("Europe/Lisbon")).toLocalDate()
        val tokyo = now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
        val first = QuoteRules.pick(QuoteOrder(), ids, lisbon)
        assertEquals("b", QuoteRules.pick(first, ids, tokyo).current)
        assertEquals("b", QuoteRules.pick(first, ids, lisbon.plusDays(1)).current)
    }
    @Test fun aLongGapAdvancesOnceWithoutPretendingUnseenQuotesWereShown() {
        val first = QuoteRules.pick(QuoteOrder(), ids, day)
        assertEquals("b", QuoteRules.pick(first, ids, day.plusDays(90)).current)
    }
    @Test fun duplicatedAndStaleOrderIdsAreReconciled() {
        val order = QuoteOrder("old", "gone", listOf("b", "gone", "b"), setOf("gone", "a"))
        val picked = QuoteRules.pick(order, ids + "a", day)
        assertEquals("b", picked.current)
        assertEquals(listOf("c"), picked.remaining)
        assertEquals(setOf("a", "b"), picked.shown)
    }
    @Test fun starterEditsAreSeparateAndRestoreOnlyBringsBackOriginalStarters() {
        val starter = Quote("starter-a", "Original")
        val personal = Quote("own-a", "My version")
        val edited = QuoteLibrary().save(personal, starter.id)
        assertEquals(listOf(personal), edited.visible(listOf(starter)))
        val restored = edited.restoreStarters(listOf(starter))
        assertEquals(listOf(starter, personal), restored.visible(listOf(starter)))
        assertEquals(listOf(starter), restored.delete(personal.id).visible(listOf(starter)))
        assertEquals(listOf(personal), restored.delete(starter.id).visible(listOf(starter)))
        assertTrue(edited.hide(personal.id).restoreStarters(listOf(starter)).hidden.contains(personal.id))
    }
    @Test fun textLengthAndOptionalCreditsAreValidated() {
        assertTrue(QuoteRules.valid(Quote("own-a", "x".repeat(2000))))
        assertFalse(QuoteRules.valid(Quote("own-a", "x".repeat(2001))))
        assertFalse(QuoteRules.valid(Quote("own-a", " \n ")))
        assertFalse(QuoteRules.valid(Quote("own-a", "text", author = "a".repeat(241))))
        assertEquals("A · W · Translated by T", Quote("a", "Text", "A", "W", "T").credit)
    }
    @Test fun shareOnlyAcceptsPlainTextSendAndKeepsOverlengthTextForEditing() {
        assertEquals("Selected text", QuoteRules.sharedText("android.intent.action.SEND", "text/plain", "Selected text"))
        assertNull(QuoteRules.sharedText("android.intent.action.VIEW", "text/plain", "Text"))
        assertNull(QuoteRules.sharedText("android.intent.action.SEND", "text/html", "<b>Text</b>"))
        assertNull(QuoteRules.sharedText("android.intent.action.SEND", "text/plain", null))
        assertNull(QuoteRules.sharedText("android.intent.action.SEND", "text/plain", "  "))
        assertEquals(2001, QuoteRules.sharedText("android.intent.action.SEND", "text/plain", "a".repeat(2001))!!.length)
    }
}
