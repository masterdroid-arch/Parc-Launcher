package com.brightblu.parc

import com.brightblu.parc.model.Skin
import org.junit.Assert.*
import org.junit.Test

class T117Test {
    @Test fun edgeSwipeUsesMonotonicClockAndVelocity() {
        val gate = SettingsExitGesture()
        gate.down(180f, 760f, 853f, 1000)
        assertTrue(gate.release(185f, 600f, 1180))
        assertFalse(gate.release(185f, 600f, 1181))
        gate.down(180f, 760f, 853f, 2000)
        assertFalse(gate.release(180f, 600f, 3000))
    }
    @Test fun scrollingAndNavigationStripsAreNotEdgeExit() {
        val gate = SettingsExitGesture()
        gate.down(180f, 400f, 853f, 100)
        assertFalse(gate.release(180f, 100f, 300))
        gate.down(180f, 830f, 853f, 100)
        assertFalse(gate.release(180f, 500f, 300))
        gate.down(180f, 760f, 853f, 100)
        assertFalse(gate.release(350f, 700f, 180))
    }
    @Test fun flingMustBeginAtEndAndHaveEnoughVelocityAndTravel() {
        val gate = SettingsExitGesture()
        gate.down(180f, 400f, 853f, 0)
        gate.scroll(-40f, 0f); gate.scroll(0f, -100f)
        assertFalse(gate.fling(-1000f))
        gate.down(180f, 400f, 853f, 1000)
        gate.scroll(0f, -80f)
        assertFalse(gate.fling(-300f))
        assertTrue(gate.fling(-1000f))
        assertFalse(gate.fling(-1000f))
    }
    @Test fun cardsMarkSelectionAndCustomEditingAndEndWithCreate() {
        val custom = Skin.Midnight.copy(id = "custom_test", name = "Test")
        val cards = skinCards(listOf(Skin.Midnight, custom), custom.id)
        assertFalse(cards[0].selected); assertFalse(cards[0].editable)
        assertTrue(cards[1].selected); assertTrue(cards[1].editable)
        assertNull(cards.last().skin); assertEquals("Make your own", cards.last().name)
        assertFalse(cards.last().selected); assertFalse(cards.last().editable)
    }
    @Test fun browsingCardsDoesNotMutateInputOrSelection() {
        val skins = listOf(Skin.Midnight, Skin.Midnight.copy(id = "other"))
        val original = skins.toList()
        skinCards(skins, "other")
        assertEquals(original, skins)
        assertEquals("midnight", skins.first().id)
    }
}
