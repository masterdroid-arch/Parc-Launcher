package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T260: first-run hints for the hidden gestures. */
class T260Test {
    @Test
    fun hintsComeOneAtATimeInOrderAndNeverTwice() {
        assertEquals(GestureHint.TurnArc, GestureHintRules.nextOnHome(emptySet()))
        assertEquals(GestureHint.OpenSearch, GestureHintRules.nextOnHome(setOf("turn_arc")))
        assertEquals(GestureHint.EditMode, GestureHintRules.nextOnHome(setOf("turn_arc", "search", "hub")))
        assertNull(GestureHintRules.nextOnHome(setOf("turn_arc", "search", "hub", "edit")))
        // The page-name hint is the hub's and is never a Home hint.
        assertFalse(GestureHint.PageName in GestureHintRules.homeOrder)
    }

    @Test
    fun thePageNameHintNeedsPagesToReorderAndIsOnceOnly() {
        assertFalse(GestureHintRules.pageNameDue(emptySet(), 1))
        assertTrue(GestureHintRules.pageNameDue(emptySet(), 2))
        assertFalse(GestureHintRules.pageNameDue(setOf("page_name"), 5))
    }

    @Test
    fun skipMarksEveryHintAndTheIdsAreStableAndDistinct() {
        assertEquals(GestureHint.entries.size, GestureHintRules.allIds().size)
        assertEquals(setOf("turn_arc", "search", "hub", "edit", "page_name"), GestureHintRules.allIds())
        GestureHint.entries.forEach { assertTrue(it.text.length in 10..90) }
    }

    @Test
    fun settingsCanShowTheHintsAgain() {
        assertTrue(SettingsRegistry.all.any { it.id == "ShowHintsAgain" })
        assertTrue(File("src/main/kotlin/com/brightblu/parc/ParcSettings.kt").readText().contains("resetGestureHints"))
        assertTrue(File("../PRIVACY.md").readText().contains("gesture hints"))
    }
}
