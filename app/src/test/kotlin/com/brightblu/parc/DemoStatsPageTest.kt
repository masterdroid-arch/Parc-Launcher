package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Test

/** T121: the demo page is a demo-only route; the real hub's page list is unchanged. */
class DemoStatsPageTest {

    private val configured = listOf("clock", "system", "power", "apps", "comms")

    @Test
    fun demoOffKeepsTheConfiguredPagesExactly() {
        assertEquals(configured, hubPagesFor(demo = false, configured = configured))
    }

    @Test
    fun demoOffStillAppliesTheFocusFilter() {
        val allowed = setOf("system", "apps")
        assertEquals(listOf("system", "apps"), hubPagesFor(demo = false, configured = configured) { it in allowed })
    }

    @Test
    fun demoOnShowsTheFourDemoPages() {
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = configured))
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = emptyList()))
        assertEquals(listOf("clock", "power", "notes", "focus", "quicktoggles") + (if (WeatherVariant.Available) listOf("weather") else emptyList()), DemoHubPages)
    }

    @Test
    fun demoOnIgnoresTheFocusFilter() {
        assertEquals(DemoHubPages, hubPagesFor(demo = true, configured = configured) { false })
    }

    @Test
    fun headerTitle() {
        assertEquals("Power", hubPageTitle(demo = true, page = "power"))
        assertEquals("Focus", hubPageTitle(demo = true, page = "focus"))
        assertEquals("System", hubPageTitle(demo = false, page = "system"))
        assertEquals("Stats", hubPageTitle(demo = false, page = null))
    }
}
