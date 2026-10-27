package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.model.AppId
import org.junit.Assert.*
import org.junit.Test

class T105PickerTest {
    @Test fun savedMainAppChoiceSurvivesWhileItsLabelAndPickerRowAreHidden() {
        val id = AppId("private.pkg/Main@0")
        val state = MainAppsState(options = listOf(MainAppChoice(id,"Private label","private.pkg")), selected = listOf(id), loaded = true)
        val shown = state.withoutLocked(setOf("private.pkg"))
        assertTrue(shown.visible.isEmpty()); assertNull(shown.slots.first())
        assertEquals(listOf(id), MainAppsPicker.confirmed(shown))
        assertEquals("Private label", state.withoutLocked(emptySet()).slots.first()!!.label)
    }
}
