package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class ArcReleaseTest {
    @Test fun tightMaximumFlingCarriesOneSlot() {
        assertEquals(11, ArcRelease.target(10, 10f, 7f, SpinLimit.Tight.slots))
    }

    @Test fun balancedMaximumFlingCarriesThreeSlots() {
        assertEquals(13, ArcRelease.target(10, 10f, 14f, SpinLimit.Balanced.slots))
    }

    @Test fun freeMaximumFlingCarriesFiveSlots() {
        assertEquals(15, ArcRelease.target(10, 10f, 28f, SpinLimit.Free.slots))
    }

    @Test fun zeroVelocityRoundsToNearestWithHalfUpTies() {
        assertEquals(1, ArcRelease.target(0, 1.49f, 0f, 4))
        assertEquals(2, ArcRelease.target(0, 1.5f, 0f, 4))
        assertEquals(-1, ArcRelease.target(0, -1.5f, 0f, 4))
    }

    @Test fun sevenSlotDragReturnsToFourSlotLimit() {
        assertEquals(14, ArcRelease.target(10, 17f, 0f, 4))
        assertEquals(6, ArcRelease.target(10, 3f, -14f, 4))
    }

    @Test fun velocityIsCappedInBothDirections() {
        assertEquals(14f, ArcRelease.clampVelocity(100f, 4), 0f)
        assertEquals(-14f, ArcRelease.clampVelocity(-100f, 4), 0f)
        assertEquals(2f, ArcRelease.clampVelocity(2f, 4), 0f)
    }

    @Test fun nonFiniteInputsUseSafeDefaults() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, ArcRelease.clampVelocity(invalid, 4), 0f)
            assertEquals(10, ArcRelease.target(10, invalid, invalid, 4))
            assertEquals(13, ArcRelease.target(10, invalid, 14f, 4))
        }
    }

    @Test fun preferencesHaveStableIdsTokensAndDefaults() {
        assertEquals(ArcFeel(SpinLimit.Balanced, Bounce.Balanced, Magnify.Subtle), ArcFeel())
        assertEquals(listOf("tight", "balanced", "free"), SpinLimit.entries.map { it.id })
        assertEquals(listOf(2, 4, 8), SpinLimit.entries.map { it.slots })
        assertEquals(listOf("calm", "balanced", "playful"), Bounce.entries.map { it.id })
        assertEquals(listOf(0.90f, 0.72f, 0.55f), Bounce.entries.map { it.dampingRatio })
        assertEquals(listOf("off", "subtle", "dock"), Magnify.entries.map { it.id })
        for (value in SpinLimit.entries) assertEquals(value, SpinLimit.fromId(value.id))
        for (value in Bounce.entries) assertEquals(value, Bounce.fromId(value.id))
        for (value in Magnify.entries) assertEquals(value, Magnify.fromId(value.id))
        for (id in listOf(null, "", "unknown")) {
            assertEquals(SpinLimit.Balanced, SpinLimit.fromId(id))
            assertEquals(Bounce.Balanced, Bounce.fromId(id))
            assertEquals(Magnify.Subtle, Magnify.fromId(id))
        }
        assertEquals(0.18f, ArcRelease.CarrySeconds, 0f)
        assertEquals(3.5f, ArcRelease.VelocityCapFactor, 0f)
        assertEquals(320f, ArcRelease.SettleStiffness, 0f)
    }
}
