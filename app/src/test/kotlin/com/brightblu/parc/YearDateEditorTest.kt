package com.brightblu.parc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T280: the add-a-date flow behaves, not just the source text. These tests start from "Add a date" pressed,
 * type a name, pick a day, save, and check the row the Year page will draw; then they change and delete it.
 */
class YearDateEditorTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun d(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    @Test fun addStartsOnTodayWithAnEmptyName() {
        val draft = YearDateEditor.add(today)
        assertNull(draft.index)
        assertEquals("", draft.name)
        assertEquals(today, draft.date)
        assertFalse(draft.yearly)
        assertFalse(draft.canSave)
        assertEquals("Add a date", draft.title)
        assertEquals("Add date", draft.saveLabel)
    }

    @Test fun theAddFlowSavesThePickedDate() {
        val typed = YearDateEditor.add(today).named("Exam").onMonth(12).onDay(23)
        val saved = YearDateEditor.save(typed, emptyList())
        assertEquals(listOf(Countdown("Exam", d(2026, 12, 23))), saved?.list)
        assertEquals(0, saved!!.index)
    }

    @Test fun savingWithNoNameDoesNothingAndSaysWhy() {
        val draft = YearDateEditor.add(today).onDay(20)
        assertNull(YearDateEditor.save(draft, emptyList()))
        assertEquals(YearDateEditor.NoName, YearDateEditor.problem(draft, emptyList()))
        assertFalse(draft.canSave)
        assertEquals(YearDateEditor.NoName, YearDateEditor.problem(draft.refused(), emptyList()))
    }

    @Test fun aWhitespaceOnlyNameIsNotAName() {
        val draft = YearDateEditor.add(today).named("   \n  ")
        assertFalse(draft.canSave)
        assertNull(YearDateEditor.save(draft, emptyList()))
    }

    @Test fun theSavedDateIsTheRowThePageShows() {
        val saved = YearDateEditor.save(YearDateEditor.add(today).named("Exam").onMonth(12).onDay(23), emptyList())!!
        val rows = Countdowns.upcoming(saved.list, today)
        assertEquals(listOf("Exam · 35 days · 23 Dec"), rows.map { Countdowns.rowText(it, today) })
    }

    @Test fun changingADateReplacesItInPlace() {
        val existing = listOf(Countdown("Exam", d(2026, 12, 23)), Countdown("Trip", d(2027, 1, 9)))
        val changed = YearDateEditor.edit(0, existing[0]).named("Final exam").onDay(24)
        val saved = YearDateEditor.save(changed, existing)!!
        assertEquals(listOf(Countdown("Final exam", d(2026, 12, 24)), existing[1]), saved.list)
        assertEquals(0, saved.index)
        assertEquals("Change this date", changed.title)
        assertEquals("Save changes", changed.saveLabel)
    }

    @Test fun editingStartsFromTheSavedDate() {
        val yearly = Countdown("Birthday", d(2000, 3, 4), yearly = true)
        val draft = YearDateEditor.edit(0, yearly)
        assertEquals(0, draft.index)
        assertEquals("Birthday", draft.name)
        assertEquals(d(2000, 3, 4), draft.date)
        assertTrue(draft.yearly)
        assertEquals(yearly, YearDateEditor.save(draft, listOf(yearly))?.list?.single())
    }

    @Test fun everyYearSurvivesTheAddFlow() {
        val saved = YearDateEditor.save(YearDateEditor.add(today).named("Birthday").onMonth(3).onDay(4).withYearly(true), emptyList())!!
        assertTrue(saved.list.single().yearly)
        assertEquals(d(2027, 3, 4), saved.list.single().next(today))
    }

    @Test fun aDayPastTheMonthEndIsTheLastDay() {
        val february = YearDateEditor.add(today).named("Leap").onMonth(2).onDay(31)
        assertEquals(d(2026, 2, 28), february.date)
        val leap = YearDateEditor.add(today).named("Leap").onMonth(2).onDay(31).onYear(2028)
        assertEquals(d(2028, 2, 29), leap.date)
    }

    @Test fun theDateIsClampedButThePickedDayIsKept() {
        val march = YearDateEditor.add(today).named("Trip").onMonth(1).onDay(31).onMonth(2)
        assertEquals(d(2026, 2, 28), march.date)
        assertEquals(31, march.day)
        assertEquals(d(2026, 3, 31), march.onMonth(3).date)
    }

    @Test fun theMostDatesIsEnforced() {
        val full = (1..Countdowns.MaxCount).map { Countdown("Date $it", today.plusDays(1)) }
        assertFalse(YearDateEditor.canAdd(full))
        assertEquals(YearDateEditor.NoRoom, YearDateEditor.addBlocked(full))
        val draft = YearDateEditor.add(today).named("One too many")
        assertNull(YearDateEditor.save(draft, full))
        assertEquals(YearDateEditor.NoRoom, YearDateEditor.problem(draft, full))
        // Editing an existing date still works when the list is full.
        val changed = YearDateEditor.save(YearDateEditor.edit(0, full[0]).named("Renamed"), full)
        assertEquals(Countdowns.MaxCount, changed!!.list.size)
    }

    @Test fun deleteRemovesOnlyTheChosenDate() {
        val existing = listOf(Countdown("A", d(2026, 12, 1)), Countdown("B", d(2026, 12, 2)), Countdown("C", d(2026, 12, 3)))
        assertEquals(listOf(existing[0], existing[2]), YearDateEditor.delete(1, existing))
        assertEquals(existing, YearDateEditor.delete(9, existing))
    }

    @Test fun namesAreCleanedAndCapped() {
        assertEquals("A B", YearDateEditor.add(today).named("  A   B  ").cleanName)
        val long = "x".repeat(Countdowns.MaxNameLength + 10)
        assertEquals(Countdowns.MaxNameLength, YearDateEditor.add(today).named(long).cleanName.length)
    }

    @Test fun aDateWhosePositionDisappearedIsAddedNotDropped() {
        val stale = YearDateEditor.edit(3, Countdown("Gone", d(2026, 12, 1)))
        val saved = YearDateEditor.save(stale, emptyList())
        assertEquals(listOf(Countdown("Gone", d(2026, 12, 1))), saved?.list)
    }

    @Test fun editingOutOfRangeOnAFullListIsRefusedWithAReason() {
        val full = (1..Countdowns.MaxCount).map { Countdown("Date $it", today.plusDays(1)) }
        val stale = YearDateEditor.edit(Countdowns.MaxCount + 2, Countdown("Gone", d(2026, 12, 1)))
        assertNull(YearDateEditor.save(stale, full))
        assertEquals(YearDateEditor.NoRoom, YearDateEditor.problem(stale, full))
    }
}
