package com.brightblu.parc

import com.brightblu.parc.feature.search.SearchModeIds
import com.brightblu.parc.feature.search.SearchModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** T194: the internet build lists Translate between Apps and Note, and "tr " switches to it. */
class T194SearchModesTest {
    @Test fun translateIsListedFirstAfterApps() {
        val modes = SearchModes.standard(WeatherVariant.extraSearchModes())
        assertEquals(listOf(SearchModeIds.Translate, SearchModeIds.Note, SearchModeIds.Calculate), modes.map { it.id })
        assertNotNull(SearchModes.detectPrefix("tr hello", modes))
    }
}
