package com.brightblu.parc.model

import org.junit.Assert.*
import org.junit.Test

class SpringsTest {
    @Test fun designTable() {
        assertEquals(SpringSpec(0.8f, 900f), Springs.press)
        assertEquals(SpringSpec(0.62f, 380f), Springs.open)
        assertEquals(SpringSpec(0.88f, 260f), Springs.sheet)
        assertEquals(SpringSpec(0.8f, 400f), Springs.glow)
    }
    @Test fun delaysAndInvalidIndices() {
        assertEquals(listOf(0, 25, 50, 75, 100), (0..4).map { Springs.openDelayMs(it, 5) })
        for ((i, n) in listOf(0 to 1, 0 to 0, -1 to 5, 5 to 5, 0 to -1)) {
            assertEquals(0, Springs.openDelayMs(i, n))
        }
        assertEquals(100, Springs.openDelayMs(Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }
    @Test fun bounceMapping() {
        for (d in listOf(0.90f, 0.72f, 0.55f)) assertEquals(SpringSpec(d, 320f), Springs.settle(d))
    }
    @Test fun skinSpeedSquaresStiffness() {
        assertEquals(243.2f, Springs.scaledStiffness(380f, 0.8f), 0.001f)
        assertEquals(380f, Springs.scaledStiffness(380f, 1f), 0f)
        assertEquals(547.2f, Springs.scaledStiffness(380f, 1.2f), 0.001f)
    }
}
