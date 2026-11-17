package com.brightblu.parc

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** M13 review: the countdown date is picked with steppers, not typed. */
class CountdownPickerTest {
    @Test fun pickedDateClampsToTheMonthEnd() {
        assertEquals(LocalDate.of(2026, 2, 28), Countdowns.pickedDate(2026, 2, 31))
        assertEquals(LocalDate.of(2028, 2, 29), Countdowns.pickedDate(2028, 2, 31))
        assertEquals(LocalDate.of(2026, 4, 30), Countdowns.pickedDate(2026, 4, 31))
        assertEquals(LocalDate.of(2026, 12, 23), Countdowns.pickedDate(2026, 12, 23))
    }
    @Test fun firstYearIsThisYearOrAnEarlierEditedDate() {
        val today = LocalDate.of(2026, 11, 10)
        assertEquals(2026, Countdowns.pickerFirstYear(today, null))
        assertEquals(2026, Countdowns.pickerFirstYear(today, LocalDate.of(2027, 3, 1)))
        assertEquals(2024, Countdowns.pickerFirstYear(today, LocalDate.of(2024, 3, 1)))
    }
}
