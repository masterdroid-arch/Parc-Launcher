package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusOverride
import com.brightblu.parc.core.data.focus.FocusRules
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T123: the focus sample is demo-only, generic, and never reaches the real focus store. */
class DemoFocusModesTest {

    @Test
    fun demoOffNeverReturnsTheSampleOrCallsAStore() {
        assertTrue(DemoFocusModes.modesIfDemo(false).isEmpty())
        assertNull(DemoFocusModes.activeNameIfDemo(false))

        val real = RecordingSink()
        assertFalse(runBlocking { DemoFocusModes.install(false, real) })
        assertEquals(0, real.touched)
    }

    @Test
    fun demoOnWritesOnlyTheDemoStoreAndTheRealStoreStaysUntouched() {
        val real = RecordingSink()
        val demo = RecordingSink()

        assertTrue(runBlocking { DemoFocusModes.install(true, demo) })

        assertEquals(1, demo.touched)
        assertEquals(DemoFocusModes.names, demo.modes.map { it.name })
        assertEquals(DemoFocusModes.ActiveName, demo.activeName)
        assertEquals(0, real.touched)
        assertTrue(real.modes.isEmpty())
        assertNull(real.activeName)
    }

    @Test
    fun sampleHasThreeNamedModesEachWithAGenericAppListAndSchedule() {
        val modes = DemoFocusModes.modesIfDemo(true)

        assertEquals(listOf("Work", "Sleep", "Reading"), modes.map { it.name })
        assertTrue(modes.all { it.enabled })
        assertTrue(modes.all { it.schedule.enabled && it.schedule.days.isNotEmpty() })
        assertTrue(modes.all { it.hiddenPackages.size in 2..4 })
        assertTrue(modes.flatMap { it.hiddenPackages }.all { it.startsWith("parc.demo.") })
        assertEquals(DemoFocusModes.ActiveName, DemoFocusModes.activeNameIfDemo(true))
    }

    @Test
    fun sampleNeverSyncsDoNotDisturbOrNamesARealApp() {
        assertTrue(DemoFocusModes.sample.none { it.syncDnd })
        val packages = DemoFocusModes.sample.flatMap { it.hiddenPackages }.toSet()
        assertTrue(DemoFocusModes.sample.all { it.hiddenPackages.size == it.hiddenPackages.toList().size })
        assertTrue(packages.all { it == it.lowercase() && it.count { ch -> ch == '.' } == 2 })
    }

    @Test
    fun sampleStartsOneModeActiveEvenOutsideItsSchedule() {
        val zone = ZoneId.of("UTC")
        // 08:00 on a Wednesday: Work starts at 09:00, Sleep ended at 07:00, Reading is weekend only.
        val now = ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val resolution = FocusRules.resolve(
            nowMillis = now,
            modes = DemoFocusModes.sample,
            override = FocusOverride(DemoFocusModes.ActiveName, on = true, untilMillis = now + 3_600_000L),
            zone = zone,
        )

        assertEquals("Work", resolution.mode?.name)
        assertTrue(resolution.manual)
    }

    private class RecordingSink : FocusSampleSink {
        var touched = 0
            private set
        var modes: List<FocusMode> = emptyList()
            private set
        var activeName: String? = null
            private set

        override suspend fun install(modes: List<FocusMode>, activeName: String) {
            touched++
            this.modes = modes
            this.activeName = activeName
        }
    }
}
