package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** T219: the Focus chip is pure state, so all four states and the pinned/unpinned tap are pinned here. */
class FocusChipTest {
    private val utc = ZoneId.of("UTC")
    private val until = Instant.parse("2026-11-11T17:00:00Z").toEpochMilli()

    @Test fun noModesAndNoActiveModeHideTheChip() {
        val state = FocusChip.state(activeName = null, untilMillis = null, accent = null,
            available = false, pinned = false, zone = utc)

        assertFalse(state.visible)
        assertEquals("", state.label)
    }

    @Test fun anAvailableButOffFocusShowsTheOffLabelAndOpensSettingsWhenUnpinned() {
        val state = FocusChip.state(activeName = null, untilMillis = null, accent = null,
            available = true, pinned = false, zone = utc)

        assertTrue(state.visible)
        assertFalse(state.active)
        assertEquals("Focus off", state.label)
        assertNull(state.dotAccent)
        assertFalse(state.opensFocusPage)
        assertEquals("Focus off. Tap to open Focus settings.", state.description)
    }

    @Test fun anActiveModeWithoutAnEndUsesItsName() {
        val state = FocusChip.state(activeName = "Work", untilMillis = null, accent = 0xFFEE5A2BL,
            available = true, pinned = true, zone = utc)

        assertTrue(state.visible)
        assertTrue(state.active)
        assertEquals("Work", state.label)
        assertEquals(0xFFEE5A2BL, state.dotAccent)
        assertTrue(state.opensFocusPage)
        assertEquals("Focus. Work. Tap to open Focus.", state.description)
    }

    @Test fun anActiveModeWithAnEndShowsUntilInLocalHoursAndMinutes() {
        val state = FocusChip.state(activeName = "Work", untilMillis = until, accent = null,
            available = true, pinned = false, zone = utc)

        assertEquals("Work · until 17:00", state.label)
        assertEquals("Focus. Work until 17:00. Tap to open Focus settings.", state.description)
        assertFalse(state.opensFocusPage)
    }

    @Test fun anActiveModeShowsEvenBeforeTheModesListHasLoaded() {
        val state = FocusChip.state(activeName = "Sleep", untilMillis = until, accent = null,
            available = false, pinned = true, zone = utc)

        assertTrue(state.visible)
        assertEquals("Sleep · until 17:00", state.label)
    }

    @Test fun theTimeIsZeroPaddedAndIndependentOfLocale() {
        val nineFive = Instant.parse("2026-11-11T09:05:00Z").toEpochMilli()

        assertEquals("09:05", FocusChip.time(nineFive, utc))
    }

    @Test fun focusIsNotInTheNewUserDefaultTabs() {
        assertFalse("focus" in HubCatalog.defaultTabs)
    }
}
