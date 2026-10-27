package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `usageSlots` DataStore value (T34): format, tolerance and round trip. */
class UsageSlotStoreTest {

    private val slots = listOf(SlotPosition("A2", 0), SlotPosition("A2", 1), SlotPosition("A2", 2), SlotPosition("A2", 3))

    @Test
    fun `the default layer is the four learned A2 slots`() {
        assertEquals(slots, DefaultUsageSlots)
        assertEquals("A2:0,A2:1,A2:2,A2:3", encodeUsageSlots(DefaultUsageSlots))
    }

    @Test
    fun `a slot list round trips through its text form`() {
        val mixed = listOf(SlotPosition("A2", 0), SlotPosition("A3", 6), SlotPosition("A4", 1))

        assertEquals(mixed, decodeUsageSlots(encodeUsageSlots(mixed)))
        assertEquals("A2:0,A3:6,A4:1", encodeUsageSlots(mixed))
    }

    @Test
    fun `a missing, blank, malformed or repeated value falls back to the default layer`() {
        val untrusted = listOf(
            null,
            "",
            "   ",
            "A2",
            "A2:",
            ":0",
            "A2:x",
            "A2:-1",
            "A2:0,",
            "A2:0,A2:0",
        )

        untrusted.forEach { value ->
            assertEquals("value <$value>", DefaultUsageSlots, decodeUsageSlots(value))
        }
    }

    @Test
    fun `a value with a different shape is read as asked`() {
        assertEquals(listOf(SlotPosition("A3", 2)), decodeUsageSlots(" A3:2 "))
    }

    @Test
    fun `an unset pref reads as the default layer and no day`() = runTest {
        val store = PrefsUsageSlotStore(ParcPrefs(FakePreferencesStore()))

        assertEquals(UsageSlotState(), store.read())
        assertEquals(DefaultUsageSlots, store.read().slots)
        assertNull(store.read().lastRankDay)
    }

    @Test
    fun `the store round trips slots and the day marker`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())
        val store = PrefsUsageSlotStore(prefs)
        val state = UsageSlotState(listOf(SlotPosition("A2", 0), SlotPosition("A2", 3)), lastRankDay = 20_300)

        store.write(state)

        assertEquals(state, store.read())
        assertEquals("A2:0,A2:3", prefs.usageSlots.first())
        assertEquals(20_300L, prefs.usageRankDay.first())
    }

    @Test
    fun `a malformed stored value is read as the default layer`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())
        prefs.setUsageSlots("nonsense")

        assertEquals(DefaultUsageSlots, PrefsUsageSlotStore(prefs).read().slots)
    }

    @Test
    fun `writing slots without a day leaves the stored day alone`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())
        val store = PrefsUsageSlotStore(prefs)
        store.write(UsageSlotState(DefaultUsageSlots, lastRankDay = 7))

        store.write(UsageSlotState(DefaultUsageSlots, lastRankDay = null))

        assertEquals(7L, prefs.usageRankDay.first())
    }
}
