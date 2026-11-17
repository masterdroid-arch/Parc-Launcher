package com.brightblu.parc

import com.brightblu.parc.RhythmLayout.Placement
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.rhythm.RhythmView
import com.brightblu.parc.model.Metrics
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1: the Rhythm page sizes itself from the room it has, and says what it shows. */
class RhythmLayoutTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun standardFitsTheRingAndItsFactsBesideItWithinTheHeight() {
        for (height in listOf(176f, 184f, 200f, 240f)) {
            val plan = RhythmLayout.plan(HubSize.Standard, height, 360f, 1f)
            assertEquals(Placement.FactsBeside, plan.placement)
            assertTrue(plan.hourLabels)
            assertTrue("ring ${plan.ringDp} at $height", plan.ringDp >= Metrics.rhythmRingMinDp)
            assertTrue(plan.ringDp <= Metrics.rhythmRingStandardDp)
            // The header row and the ring with its labels fit the height whenever the minimum ring can.
            if (height - Metrics.rhythmHeaderDp - 2f * Metrics.rhythmLabelBandDp >= Metrics.rhythmRingMinDp)
                assertTrue(plan.boxDp + Metrics.rhythmHeaderDp <= height + 0.001f)
        }
    }

    @Test fun theRingGrowsWithTheRoomButNeverPastItsMaximum() {
        val small = RhythmLayout.plan(HubSize.Standard, 190f, 360f, 1f).ringDp
        val big = RhythmLayout.plan(HubSize.Standard, 260f, 360f, 1f).ringDp
        assertTrue(big >= small)
        assertEquals(Metrics.rhythmRingStandardDp, big, 0f)
        assertEquals(Metrics.rhythmRingTallDp, RhythmLayout.plan(HubSize.Tall, 900f, 360f, 1f).ringDp, 0f)
    }

    @Test fun compactIsJustTheRingWithItsCentreNumber() {
        val plan = RhythmLayout.plan(HubSize.Compact, 80f, 360f, 1f)
        assertEquals(Placement.CompactBeside, plan.placement)
        assertFalse(plan.hourLabels)
        assertEquals(0f, plan.bandDp, 0f)
        assertEquals(80f, plan.ringDp, 0f)
        assertEquals(Metrics.rhythmRingCompactMinDp, RhythmLayout.plan(HubSize.Compact, 20f, 360f, 1f).ringDp, 0f)
    }

    @Test fun tallPutsTheFactsUnderTheRingAndALargeFontShrinksTheRing() {
        val plan = RhythmLayout.plan(HubSize.Tall, 330f, 360f, 1f)
        assertEquals(Placement.FactsBelow, plan.placement)
        assertTrue(plan.boxDp + Metrics.rhythmHeaderDp + Metrics.rhythmFactsRowDp <= 330f)
        assertTrue(RhythmLayout.plan(HubSize.Tall, 330f, 360f, 2f).ringDp <= plan.ringDp)
    }

    @Test fun aVeryNarrowStandardMovesTheFactsBelow() {
        assertEquals(Placement.FactsBelow, RhythmLayout.plan(HubSize.Standard, 200f, 220f, 1f).placement)
    }

    @Test fun theCentreNumberFitsTheHoleOfTheRing() {
        for (ring in listOf(64f, 72f, 100f, 148f, 184f)) {
            val plan = RhythmLayout.plan(HubSize.Standard, ring + 64f, 360f, 1f)
            val hole = plan.ringDp - 2f * plan.strokeDp
            val sp = RhythmLayout.centreSp(plan.ringDp, plan.strokeDp)
            assertTrue(sp in Metrics.hubLineSp..Metrics.hubValueSp)
            // "3h 12m" is six characters of about 0.55 em.
            if (hole > 0f && sp > Metrics.hubLineSp) assertTrue(sp * 6 * 0.55f <= hole)
        }
    }

    @Test fun theCentreNumberAndDateLineAreInPlainWords() {
        assertEquals("—", RhythmPresentation.centreNumber(null))
        assertEquals("45m", RhythmPresentation.centreNumber(45 * 60_000L))
        assertEquals("3h 12m", RhythmPresentation.centreNumber((3 * 60 + 12) * 60_000L))
        assertEquals("1h 05m", RhythmPresentation.centreNumber(65 * 60_000L))
        assertEquals("Today · Tue 10 Nov", RhythmPresentation.dateLine(today, today))
        assertEquals("Yesterday · Mon 9 Nov", RhythmPresentation.dateLine(today.minusDays(1), today))
        assertEquals("Sun 8 Nov", RhythmPresentation.dateLine(today.minusDays(2), today))
        assertEquals("Lit = screen on", RhythmPresentation.Legend)
        assertEquals(listOf("00", "06", "12", "18"), RhythmPresentation.HourLabels)
    }

    @Test fun anUnrecordedDayHasNoCentreNumber() {
        val shown = RhythmView.show(DayLedger.Empty, today.minusDays(1), today, 0L, 86_400_000L)
        assertEquals("—", RhythmPresentation.centreNumber(shown.screenOnMillis))
    }
}
