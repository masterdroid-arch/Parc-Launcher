package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Test

/** M13 review: a tapped note reminder shows the Notes page for one visit even when it is hidden. */
class HubForcedPageTest {
    private val ordered = listOf("clock", "system", "notes", "power")
    @Test fun hiddenPageIsAddedInItsPlace() = assertEquals(listOf("clock", "notes", "power"), withForcedHubPage(listOf("clock", "power"), "notes", ordered))
    @Test fun visiblePageIsUntouched() = assertEquals(listOf("clock", "notes"), withForcedHubPage(listOf("clock", "notes"), "notes", ordered))
    @Test fun nothingForcedChangesNothing() = assertEquals(listOf("clock"), withForcedHubPage(listOf("clock"), null, ordered))
    @Test fun unknownPageIsIgnored() = assertEquals(listOf("clock"), withForcedHubPage(listOf("clock"), "nope", ordered))
    @Test fun emptyListGetsJustThePage() = assertEquals(listOf("notes"), withForcedHubPage(emptyList(), "notes", ordered))
}
