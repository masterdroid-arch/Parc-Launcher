package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.1: the shared hub row, the second clock words, the Rhythm centre number and the public-source rule. */
class T272Test {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }

    @Test fun aFigureWithAWordUnitSplitsAndOtherValuesStayWhole() {
        assertEquals("13,463" to "steps", HubFigure.split("13,463 steps"))
        assertEquals("872.3" to "MB", HubFigure.split("872.3 MB"))
        assertEquals("14" to "pickups", HubFigure.split("14 pickups"))
        assertEquals("7 h 57 min" to null, HubFigure.split("7 h 57 min"))
        assertEquals("09:00" to null, HubFigure.split("09:00"))
    }

    @Test fun aFigureRowKeepsOnlyTheQuietPartOfItsCaption() {
        assertEquals("goal 8,000", TodayLine("steps", "", "Steps", "5,210", "Steps · goal 8,000").detail)
        assertEquals("Golden hour", TodayLine("sky", "", "Sky", "16:32", "Golden hour · Sky").detail)
        assertEquals("Tea", TodayLine("timers", "", "Timer", "4 min", "Timer · Tea").detail)
    }

    @Test fun theSecondClockSaysCityDayAndOffset() {
        val lisbon = ZoneId.of("Europe/Lisbon")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = java.time.ZonedDateTime.of(2026, 11, 17, 20, 0, 0, 0, lisbon).toInstant().toEpochMilli()
        assertEquals("New York", ClockDisplay.city("America/New_York"))
        assertEquals("Tomorrow", ClockDisplay.dayWord(now, lisbon, tokyo))
        assertEquals("Today", ClockDisplay.dayWord(now, lisbon, lisbon))
        assertEquals("9 h ahead", ClockDisplay.offsetText(now, lisbon, tokyo))
        assertEquals("9 h behind", ClockDisplay.offsetText(now, tokyo, lisbon))
        assertEquals("same time", ClockDisplay.offsetText(now, lisbon, lisbon))
    }

    @Test fun theRhythmCentreNumberFitsTheHoleForSevenCharactersAndBigFonts() {
        for (ring in listOf(72f, 100f, 148f)) {
            val stroke = ring * 0.1f
            val hole = ring - 2 * stroke
            for (scale in listOf(1f, 1.3f)) {
                val sp = RhythmLayout.centreSp(ring, stroke, 7, scale)
                assertTrue("ring=$ring scale=$scale", sp * scale * 7 * 0.55f <= hole || sp <= 11f)
            }
        }
    }

    @Test fun theMoreBelowSignSitsInsideTheClearPartOfTheFade() {
        assertTrue(Metrics.hubFadeDrawDp * (1f - Metrics.hubFadeClearFraction) >= 18f)
    }

    @Test fun noDefaultClockZoneInThePublicSources() {
        assertNull(PersonalExtras.defaultClockZone.takeIf { BuildConfigFlavorIsPublic })
        val offenders = listOf("app/src/main", "app/src/test", "app/src/noExtras", "app/src/noWeather", "app/src/personalWeather", "core", "feature")
            .flatMap { File(root(), it).walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "json", "xml") && !f.path.contains("/build/") }.toList() }
            .filter { it.readText().contains("Astrakhan") && !it.path.endsWith("T272Test.kt") && !it.path.contains("testPersonalExtras") }
        assertTrue("Astrakhan in: $offenders", offenders.isEmpty())
        assertFalse(File(root(), "app/src/main/kotlin/com/brightblu/parc/ExtraPages.kt").readText().contains("Astrakhan"))
    }

    private val BuildConfigFlavorIsPublic: Boolean get() = PersonalExtras.pages.isEmpty()
}
