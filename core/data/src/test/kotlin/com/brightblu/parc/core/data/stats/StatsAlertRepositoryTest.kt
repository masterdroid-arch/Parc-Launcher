package com.brightblu.parc.core.data.stats

import com.brightblu.parc.core.data.FakePreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T78 alert-threshold storage: defaults, atomic edits, clamping, tolerant decoding and a
 * round trip through DataStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsAlertRepositoryTest {

    private fun valueOf(
        thresholds: List<StatsAlertThreshold>,
        metric: StatsAlertMetric,
    ): StatsAlertThreshold = thresholds.first { it.metric == metric }

    @Test
    fun `a fresh store reads the card defaults`() = runTest {
        val repository = StatsAlertRepository(StatsAlertPrefs(FakePreferencesStore()), backgroundScope)
        runCurrent()

        assertEquals(StatsAlertThresholds.Default, repository.thresholds.value)
    }

    @Test
    fun `setting a switch and a value stores both in one write`() = runTest {
        val store = FakePreferencesStore()
        val repository = StatsAlertRepository(StatsAlertPrefs(store), backgroundScope)
        runCurrent()

        repository.set(StatsAlertMetric.BatteryTemperatureHigh, enabled = false, value = 45f)
        runCurrent()

        val threshold = valueOf(repository.thresholds.value, StatsAlertMetric.BatteryTemperatureHigh)
        assertFalse(threshold.enabled)
        assertEquals(45f, threshold.value, 0f)
        // The other three are untouched.
        assertEquals(
            StatsAlertThresholds.Default.filterNot { it.metric == StatsAlertMetric.BatteryTemperatureHigh },
            repository.thresholds.value.filterNot { it.metric == StatsAlertMetric.BatteryTemperatureHigh },
        )
    }

    @Test
    fun `setValue clamps to the metric range and ignores non-finite input`() = runTest {
        val repository = StatsAlertRepository(StatsAlertPrefs(FakePreferencesStore()), backgroundScope)
        runCurrent()

        repository.setValue(StatsAlertMetric.StorageHigh, 1000f)
        runCurrent()
        assertEquals(100f, valueOf(repository.thresholds.value, StatsAlertMetric.StorageHigh).value, 0f)

        repository.setValue(StatsAlertMetric.RamHigh, -5f)
        runCurrent()
        assertEquals(1f, valueOf(repository.thresholds.value, StatsAlertMetric.RamHigh).value, 0f)

        repository.setValue(StatsAlertMetric.BatteryLow, Float.NaN)
        runCurrent()
        assertEquals(15f, valueOf(repository.thresholds.value, StatsAlertMetric.BatteryLow).value, 0f)
    }

    @Test
    fun `setEnabled keeps the stored value`() = runTest {
        val repository = StatsAlertRepository(StatsAlertPrefs(FakePreferencesStore()), backgroundScope)
        runCurrent()

        repository.setValue(StatsAlertMetric.RamHigh, 75f)
        repository.setEnabled(StatsAlertMetric.RamHigh, false)
        runCurrent()

        val ram = valueOf(repository.thresholds.value, StatsAlertMetric.RamHigh)
        assertFalse(ram.enabled)
        assertEquals(75f, ram.value, 0f)
    }

    @Test
    fun `a second repository restores the stored thresholds`() = runTest {
        val store = FakePreferencesStore()
        val first = StatsAlertRepository(StatsAlertPrefs(store), backgroundScope)
        runCurrent()
        first.set(StatsAlertMetric.BatteryLow, enabled = true, value = 20f)
        runCurrent()

        val second = StatsAlertRepository(StatsAlertPrefs(store), backgroundScope)
        runCurrent()

        assertEquals(20f, valueOf(second.thresholds.value, StatsAlertMetric.BatteryLow).value, 0f)
    }

    @Test
    fun `reset removes the document and restores the defaults`() = runTest {
        val store = FakePreferencesStore()
        val repository = StatsAlertRepository(StatsAlertPrefs(store), backgroundScope)
        runCurrent()
        repository.setValue(StatsAlertMetric.BatteryLow, 30f)
        runCurrent()

        repository.reset()
        runCurrent()

        assertEquals(StatsAlertThresholds.Default, repository.thresholds.value)
    }

    @Test
    fun `the codec round-trips every threshold`() {
        val custom = listOf(
            StatsAlertThreshold(StatsAlertMetric.BatteryTemperatureHigh, enabled = false, value = 48f),
            StatsAlertThreshold(StatsAlertMetric.StorageHigh, enabled = true, value = 80f),
            StatsAlertThreshold(StatsAlertMetric.BatteryLow, enabled = false, value = 10f),
            StatsAlertThreshold(StatsAlertMetric.RamHigh, enabled = true, value = 95f),
        )
        assertEquals(custom, StatsAlertJson.decode(StatsAlertJson.encode(custom)))
    }

    @Test
    fun `decoding is tolerant of corrupt, unknown and out-of-range documents`() {
        assertEquals(StatsAlertThresholds.Default, StatsAlertJson.decode(null))
        assertEquals(StatsAlertThresholds.Default, StatsAlertJson.decode(""))
        assertEquals(StatsAlertThresholds.Default, StatsAlertJson.decode("not json"))
        assertEquals(
            StatsAlertThresholds.Default,
            StatsAlertJson.decode("""[{"metric":"FutureMetric","enabled":false,"value":1}]"""),
        )

        val clamped = StatsAlertJson.decode(
            """[{"metric":"RamHigh","enabled":false,"value":999}]""",
        )
        val ram = valueOf(clamped, StatsAlertMetric.RamHigh)
        assertFalse(ram.enabled)
        assertEquals(100f, ram.value, 0f)
        // Metrics the document did not mention keep their defaults.
        assertEquals(42f, valueOf(clamped, StatsAlertMetric.BatteryTemperatureHigh).value, 0f)
    }

    @Test
    fun `duplicate metrics decode as the first occurrence`() {
        val decoded = StatsAlertJson.decode(
            """[
                {"metric":"RamHigh","enabled":false,"value":70},
                {"metric":"RamHigh","enabled":true,"value":95}
            ]""",
        )
        val ram = valueOf(decoded, StatsAlertMetric.RamHigh)
        assertFalse(ram.enabled)
        assertEquals(70f, ram.value, 0f)
        assertTrue(decoded.any { it.metric == StatsAlertMetric.BatteryLow })
    }
}
