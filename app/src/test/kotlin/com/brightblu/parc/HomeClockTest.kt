package com.brightblu.parc

import com.brightblu.parc.model.ClockStyle
import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.model.TypographyTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeClockTest {
    private val doto = TypographyTokens(numeralFamily = "Doto")
    private val plain = TypographyTokens()

    @Test fun dotSkinDrawsDotMatrixForTheDefaultStyle() {
        assertEquals(ClockFace.Dot, ClockFaces.of(doto, ClockStyle.Thin))
    }

    @Test fun skinWithoutNumeralFamilyKeepsTheQuietClock() {
        assertEquals(ClockFace.Text, ClockFaces.of(plain, ClockStyle.Thin))
    }

    @Test fun theUsersClockStyleWinsOnADotSkin() {
        assertEquals(ClockFace.Text, ClockFaces.of(doto, ClockStyle.Bold))
        assertEquals(ClockFace.Text, ClockFaces.of(doto, ClockStyle.Mono))
        assertEquals(ClockFace.Digital, ClockFaces.of(doto, ClockStyle.Digital))
        assertEquals(ClockFace.Hidden, ClockFaces.of(doto, ClockStyle.Hidden))
    }

    @Test fun hiddenAndDigitalAreTheSameOnEverySkin() {
        for (t in listOf(doto, plain)) {
            assertEquals(ClockFace.Hidden, ClockFaces.of(t, ClockStyle.Hidden))
            assertEquals(ClockFace.Digital, ClockFaces.of(t, ClockStyle.Digital))
        }
    }

    @Test fun standaloneClockShowsOnlyWhenThePanelIsOff() {
        assertTrue(ClockFaces.standaloneVisible(statsPanelOn = false, clockShown = true, onHome = true, searchOpen = false, editing = false))
        assertFalse(ClockFaces.standaloneVisible(statsPanelOn = true, clockShown = true, onHome = true, searchOpen = false, editing = false))
    }

    @Test fun standaloneClockNeedsTheClockSettingAndPlainHome() {
        assertFalse(ClockFaces.standaloneVisible(false, clockShown = false, onHome = true, searchOpen = false, editing = false))
        assertFalse(ClockFaces.standaloneVisible(false, true, onHome = false, searchOpen = false, editing = false))
        assertFalse(ClockFaces.standaloneVisible(false, true, true, searchOpen = true, editing = false))
        assertFalse(ClockFaces.standaloneVisible(false, true, true, false, editing = true))
    }

    @Test fun dotNumeralsIgnoreTheFontScaleAndStayAtLeast24sp() {
        val at130 = ClockFaces.fixedDot(doto, 1.3f).clockDot.sizeSp
        assertEquals(56f, at130 * 1.3f, 0.01f)
        assertEquals(56f, ClockFaces.fixedDot(doto, 1f).clockDot.sizeSp, 0f)
        val tiny = TypographyTokens(numeralFamily = "Doto", clockDot = TypeToken(30f, 500, 0f))
        assertTrue(ClockFaces.fixedDot(tiny, 2f).clockDot.sizeSp >= 24f)
    }
}
