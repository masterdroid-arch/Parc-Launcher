package com.brightblu.parc.core.data.demo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** T121: fixture shape, derived values, formatting and the demo store's seed/repair rules. */
class DemoStatsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("demo-stats").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = DemoStatsStore(File(dir, DemoStatsStore.FileName))

    @Test
    fun fixtureHasTwoWeeksInOrder() {
        val sample = DemoStatsFixture.sample()
        assertEquals(14, sample.days.size)
        assertEquals((1..14).toList(), sample.days.map { it.day })
        assertTrue(DemoStatsRules.isValid(sample))
    }

    @Test
    fun fixtureValuesAreBelievableAndVary() {
        val sample = DemoStatsFixture.sample()
        assertTrue(sample.days.all { it.screenTimeMinutes in 60..360 })
        assertTrue(sample.days.all { it.unlocks in 20..160 })
        assertTrue(sample.days.all { it.steps in 1_000..15_000 })
        assertTrue(sample.days.all { it.batteryPercent in 20..95 })
        assertTrue("screen time should vary", sample.days.map { it.screenTimeMinutes }.distinct().size > 7)
        assertTrue("steps should vary", sample.days.map { it.steps }.distinct().size > 7)
    }

    @Test
    fun derivedValuesMatchTheSeries() {
        val sample = DemoStatsFixture.sample()
        assertEquals(213, sample.screenTimeAverageMinutes)
        assertEquals(78, sample.unlocksAverage)
        assertEquals(7_194, sample.stepsAverage)
        assertEquals(55, sample.batteryLowPercent)
        assertEquals(84, sample.batteryHighPercent)
        assertEquals(142, sample.today?.screenTimeMinutes)
        assertEquals(51, sample.today?.unlocks)
        assertEquals(4_260, sample.today?.steps)
        assertEquals(71, sample.today?.batteryPercent)
    }

    @Test
    fun averageRoundsAndHandlesEmpty() {
        assertEquals(0, DemoStatsRules.average(emptyList()))
        assertEquals(3, DemoStatsRules.average(listOf(2, 3, 4)))
        assertEquals(3, DemoStatsRules.average(listOf(2, 3)))
    }

    @Test
    fun durationFormatting() {
        assertEquals("0 min", DemoStatsFormat.duration(0))
        assertEquals("58 min", DemoStatsFormat.duration(58))
        assertEquals("1 h", DemoStatsFormat.duration(60))
        assertEquals("2 h 22 min", DemoStatsFormat.duration(142))
        assertEquals(DemoStatsFormat.Unavailable, DemoStatsFormat.duration(-1))
    }

    @Test
    fun countAndPercentFormattingAreLocaleIndependent() {
        assertEquals("4,260", DemoStatsFormat.count(4_260))
        assertEquals("213", DemoStatsFormat.count(213))
        assertEquals("71%", DemoStatsFormat.percent(71))
    }

    @Test
    fun jsonRoundTripKeepsEveryDay() {
        val sample = DemoStatsFixture.sample()
        assertEquals(sample, DemoStatsJson.decode(DemoStatsJson.encode(sample)))
    }

    @Test
    fun decodingRejectsGarbageAndImplausibleSamples() {
        assertNull(DemoStatsJson.decode("not json"))
        assertNull(DemoStatsJson.decode("""{"days":[]}"""))
        val short = DemoSample(DemoStatsFixture.sample().days.take(3))
        assertNull(DemoStatsJson.decode(DemoStatsJson.encode(short)))
        val fast = DemoSample(
            DemoSample(DemoStatsFixture.sample().days).days.mapIndexed { index, day ->
                if (index == 0) day.copy(screenTimeMinutes = 9_999) else day
            },
        )
        assertNull(DemoStatsJson.decode(DemoStatsJson.encode(fast)))
    }

    @Test
    fun firstReadSeedsTheStore() {
        val file = File(dir, DemoStatsStore.FileName)
        assertFalse(file.exists())
        val sample = store().read()
        assertTrue(file.isFile)
        assertEquals(DemoStatsFixture.sample(), sample)
    }

    @Test
    fun laterReadsUseTheStoredValues() {
        val sample = DemoStatsFixture.sample()
        val changed = sample.copy(days = sample.days.map { it.copy(steps = it.steps + 1) })
        store().write(changed)
        assertEquals(changed, store().read())
        assertNotEquals(sample, store().read())
    }

    @Test
    fun corruptFileIsReplacedByTheFixture() {
        val file = File(dir, DemoStatsStore.FileName).also { it.parentFile?.mkdirs() }
        file.writeText("{ this is not the demo store")
        assertEquals(DemoStatsFixture.sample(), store().read())
        assertEquals(DemoStatsFixture.sample(), DemoStatsJson.decode(file.readText()))
    }

    @Test
    fun implausibleStoredFileIsReplacedByTheFixture() {
        val file = File(dir, DemoStatsStore.FileName).also { it.parentFile?.mkdirs() }
        file.writeText(DemoStatsJson.encode(DemoSample(emptyList())))
        assertEquals(DemoStatsFixture.sample(), store().read())
        assertEquals(DemoStatsFixture.sample(), DemoStatsJson.decode(file.readText()))
    }
}
