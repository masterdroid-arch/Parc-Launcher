package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Test

/** T121: the demo page is a demo-only route; the real hub's page list is unchanged. */
class DemoStatsPageTest {

    private val configured = listOf(TodayPage.Id, DevicePage.Id, "apps", "comms")

    @Test
    fun demoOffKeepsTheConfiguredPagesExactly() {
        assertEquals(configured, hubPagesFor(demo = false, configured = configured))
    }

    @Test
    fun demoOffStillAppliesTheFocusFilter() {
        val allowed = setOf(DevicePage.Id, "apps")
        assertEquals(listOf(DevicePage.Id, "apps"), hubPagesFor(demo = false, configured = configured) { it in allowed })
    }

    @Test
    fun demoOnShowsTheFourDemoPages() {
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = configured))
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = emptyList()))
        assertEquals(listOf(TodayPage.Id, DevicePage.Id, ControlsPage.Id, "apps", "focus", "notes", "comms") + (if (WeatherVariant.Available) listOf("weather") else emptyList()) + listOf("steps", "rhythm", "sky", "year", "records", CommonplacePage.Id, "library") + PersonalExtras.pages.map { it.id }, DemoHubPages)
    }

    @Test
    fun demoOnIgnoresTheFocusFilter() {
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = configured) { false })
    }

    @Test
    fun headerTitle() {
        assertEquals("Device", hubPageTitle(demo = true, page = DevicePage.Id))
        assertEquals("Focus", hubPageTitle(demo = true, page = "focus"))
        assertEquals("Device", hubPageTitle(demo = false, page = "system"))
        assertEquals("Stats", hubPageTitle(demo = false, page = null))
    }
}
