package com.brightblu.parc

import com.brightblu.parc.model.Skin
import org.junit.Assert.*
import org.junit.Test

class T117Test {
    // 1.2.1: the three SettingsExitGesture tests that were here were deleted on purpose with the gesture itself.
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
