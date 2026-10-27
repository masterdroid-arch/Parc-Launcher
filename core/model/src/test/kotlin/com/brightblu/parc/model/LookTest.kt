package com.brightblu.parc.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** T71: icon masks, the icon-size clamp, clock/detail formatting and persisted look settings. */
class LookTest {
    @Test fun defaultsAreExactlyTodaysLook() {
        val look = LookSettings()
        assertEquals(IconShape.Circle, look.iconShape)
        assertEquals(Metrics.iconDp.toInt(), look.iconSizeDp)
        assertEquals(ClockStyle.Thin, look.clockStyle)
        assertFalse(look.showDate)
        assertFalse(look.showAlarm)
        assertEquals(WallpaperStyle.Black, look.wallpaper)
        assertTrue(look.wallpaperDimPercent in 0..80)
    }

    @Test fun settingsRoundTripAndUnknownFieldsAreRejected() {
        val look = LookSettings(IconShape.Squircle, 38, ClockStyle.Digital, true, true,
            WallpaperStyle.System, 70)
        assertEquals(look, LookJson.decode(LookJson.encode(look)))
        assertTrue(runCatching {
            LookJson.decode(LookJson.encode(look).replace("\"iconShape\"", "\"unknown\":0,\"iconShape\""))
        }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"iconSizeDp\":35}") }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"iconSizeDp\":37}") }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"wallpaperDimPercent\":85}") }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"wallpaperDimPercent\":45}") }.isFailure)
    }

    @Test fun iconSizeRoundsToTheNearestTwoDpStep() {
        assertEquals(36, LookRules.normalizeIconSize(30))
        assertEquals(36, LookRules.normalizeIconSize(35))
        assertEquals(36, LookRules.normalizeIconSize(36))
        assertEquals(38, LookRules.normalizeIconSize(37))
        assertEquals(44, LookRules.normalizeIconSize(44))
        assertEquals(52, LookRules.normalizeIconSize(51))
        assertEquals(52, LookRules.normalizeIconSize(52))
        assertEquals(52, LookRules.normalizeIconSize(99))
        assertEquals(LookRules.IconSizeSteps, (36..52 step 2).toList())
    }

    @Test fun effectiveIconSizeNeverExceedsTheTightestPitch() {
        assertEquals(52f, LookRules.effectiveIconSizeDp(52, 60f), 0f)
        assertEquals(52f, LookRules.effectiveIconSizeDp(52, 62f), 0f)
        assertEquals(50f, LookRules.effectiveIconSizeDp(52, 51f), 0f)
        assertEquals(44f, LookRules.effectiveIconSizeDp(44, 50f), 0f)
        assertEquals(40f, LookRules.effectiveIconSizeDp(52, 41f), 0f)
        assertEquals(36f, LookRules.effectiveIconSizeDp(36, 20f), 0f)
        assertEquals(52f, LookRules.effectiveIconSizeDp(52, Float.NaN), 0f)
    }

    @Test fun everyShapeContainsTheCentreAndTheRawShapeContainsCorners() {
        IconShape.entries.forEach { shape ->
            assertTrue("$shape centre", LookRules.maskContains(shape, 0f, 0f))
            assertTrue("$shape horizontal", LookRules.maskContains(shape, 1f, 0f))
        }
        assertFalse(LookRules.maskContains(IconShape.Circle, 0.98f, 0.98f))
        assertFalse(LookRules.maskContains(IconShape.Squircle, 0.98f, 0.98f))
        assertFalse(LookRules.maskContains(IconShape.RoundedSquare, 0.98f, 0.98f))
        assertTrue(LookRules.maskContains(IconShape.None, 0.98f, 0.98f))
    }

    @Test fun squircleAndRoundedSquareDifferAtTheirCorners() {
        // A rounded square with 22% corners is already inside at (0.85,0.85); a fourth-power
        // squircle is not. Both are outside the circle.
        assertTrue(LookRules.maskContains(IconShape.RoundedSquare, 0.85f, 0.85f))
        assertFalse(LookRules.maskContains(IconShape.Squircle, 0.85f, 0.85f))
        assertFalse(LookRules.maskContains(IconShape.Circle, 0.85f, 0.85f))
        assertFalse(LookRules.maskContains(IconShape.None, 1.01f, 0f))
    }

    @Test fun clockWeightsComeFromTheStyleNotTheSkin() {
        assertEquals(200, LookRules.clockWeight(ClockStyle.Thin, 200))
        assertEquals(300, LookRules.clockWeight(ClockStyle.Thin, 300))
        assertEquals(700, LookRules.clockWeight(ClockStyle.Bold, 200))
        assertEquals(400, LookRules.clockWeight(ClockStyle.Mono, 200))
        assertEquals(200, LookRules.clockWeight(ClockStyle.Hidden, 200))
    }

    @Test fun rowsCarryReadableLabels() {
        assertEquals("Circle · Thin", LookRules.summary(LookSettings()))
        assertEquals("Squircle · Digital",
            LookRules.summary(LookSettings(iconShape = IconShape.Squircle, clockStyle = ClockStyle.Digital)))
        assertEquals("Rounded square", LookRules.iconShapeLabel(IconShape.RoundedSquare))
        assertEquals("None", LookRules.iconShapeLabel(IconShape.None))
        assertEquals("System wallpaper", LookRules.wallpaperLabel(WallpaperStyle.System))
        assertEquals("Solid skin colour", LookRules.wallpaperLabel(WallpaperStyle.Solid))
    }

    @Test fun sliderFractionsMapToStepsAndClamp() {
        val sizes = LookRules.IconSizeSteps
        assertEquals(0, LookRules.stepIndex(-1f, sizes))
        assertEquals(0, LookRules.stepIndex(0f, sizes))
        assertEquals(sizes.lastIndex, LookRules.stepIndex(1f, sizes))
        assertEquals(sizes.indexOf(44), LookRules.stepIndex(0.5f, sizes))
        assertEquals(0, LookRules.stepIndex(Float.NaN, sizes))
        assertEquals(4, LookRules.stepIndex(0.5f, LookRules.DimSteps))
        assertEquals(80, LookRules.DimSteps[LookRules.stepIndex(1f, LookRules.DimSteps)])
    }

    @Test fun everyDigitHasTheRightSegments() {
        assertEquals(6, SevenSegment.segmentsFor('0')!!.size)
        assertEquals(setOf(ClockSegment.UpperRight, ClockSegment.LowerRight), SevenSegment.segmentsFor('1'))
        assertEquals(5, SevenSegment.segmentsFor('2')!!.size)
        assertEquals(7, SevenSegment.segmentsFor('8')!!.size)
        assertTrue(SevenSegment.segmentsFor('6')!!.contains(ClockSegment.UpperLeft))
        assertFalse(SevenSegment.segmentsFor('6')!!.contains(ClockSegment.UpperRight))
        assertTrue(SevenSegment.segmentsFor('9')!!.contains(ClockSegment.UpperRight))
        assertNull(SevenSegment.segmentsFor(':'))
        assertNull(SevenSegment.segmentsFor('x'))
        assertTrue(SevenSegment.supports("09:41"))
        assertFalse(SevenSegment.supports("09:4a"))
        assertFalse(SevenSegment.supports(""))
    }

    @Test fun dateAlarmAndDetailFormattingIsDeterministic() {
        val date = LocalDate.of(2026, 10, 15)
        assertEquals("Thu 15 Oct", LookClock.dateLine(date, Locale.ENGLISH))
        val zone = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(2026, 10, 15, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        val alarm = ZonedDateTime.of(2026, 10, 15, 7, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Alarm 07:05", LookClock.alarmLine(alarm, now, zone))
        assertEquals("", LookClock.alarmLine(null, now, zone))
        assertEquals("", LookClock.alarmLine(now - 1, now, zone))
        assertEquals("", LookClock.alarmLine(now, now, zone))
        assertEquals("Thu 15 Oct · Alarm 07:05",
            LookClock.detail(true, true, date, alarm, now, zone).text)
        assertEquals("Thu 15 Oct", LookClock.detail(true, false, date, alarm, now, zone).text)
        assertEquals("", LookClock.detail(false, false, date, alarm, now, zone).text)
    }

    @Test fun focusLineLeadsTheClockSubline() {
        val detail = ClockDetail(dateLine = "Thu 15 Oct", alarmLine = "Alarm 07:05",
            focusLine = "focus · Work")
        assertEquals("focus · Work · Thu 15 Oct · Alarm 07:05", detail.text)
        assertTrue(detail.hasText)
        assertTrue(ClockDetail().text.isEmpty())
        assertFalse(ClockDetail().hasText)
        assertEquals("focus · Work", ClockDetail(focusLine = "focus · Work").text)
    }
}
