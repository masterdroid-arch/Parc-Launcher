package com.brightblu.parc

import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T322: every hub page asks for a semantic role, and the role comes from the skin's six-role scale. */
class HubTypeScaleTest {
    private val type = Skin.Midnight.typography

    @Test fun bigNumbersUseTheStatRoleNotALiteralSize() {
        assertEquals(type.stat, HubType.figure(type))
        assertEquals(type.countDot, HubType.figure(type))
        assertEquals(28f, HubType.figure(type).sizeSp, 0f)
        assertEquals(600, HubType.figure(type).weight)
    }

    @Test fun aFigureAndItsLabelReadAsOneUnit() {
        val label = HubType.figureLabel(type)
        assertEquals(type.body, label)
        assertEquals(15f, label.sizeSp, 0f)
        assertEquals(400, label.weight)
        assertTrue("a figure label must be larger than a caption", label.sizeSp > HubType.caption(type).sizeSp)
    }

    @Test fun monthInitialsNeverFallBelowCaption() {
        val initial = HubType.monthInitial(type)
        assertEquals(type.caption, initial)
        assertTrue(initial.sizeSp >= type.caption.sizeSp)
        assertTrue(initial.sizeSp >= 11f)
    }

    @Test fun numeralsTakeAFigureRoleAndWordsDoNot() {
        for (value in listOf("1675", "17:53", "64", "100%", "4°", "3h 12m")) {
            assertTrue(value, HubType.isFigureNumeral(value))
        }
        for (value in listOf("Ready", "Nothing waiting", "—", "City time")) {
            assertFalse(value, HubType.isFigureNumeral(value))
        }
    }

    @Test fun theSixRolesMatchTheApprovedScale() {
        assertEquals(28f, HubType.heading(type).sizeSp, 0f)
        assertEquals(300, HubType.heading(type).weight)
        assertEquals(17f, HubType.section(type).sizeSp, 0f)
        assertEquals(500, HubType.section(type).weight)
        assertEquals(15f, HubType.body(type).sizeSp, 0f)
        assertEquals(400, HubType.body(type).weight)
        assertEquals(11f, HubType.caption(type).sizeSp, 0f)
        assertEquals(500, HubType.caption(type).weight)
        assertEquals(44f, type.clock.sizeSp, 0f)
        assertEquals(200, type.clock.weight)
    }
}
