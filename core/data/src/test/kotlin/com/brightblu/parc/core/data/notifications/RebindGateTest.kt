package com.brightblu.parc.core.data.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebind rate limit: the card requires a rebind on disconnect, and a flapping listener must not
 * turn that into a request storm.
 */
class RebindGateTest {

    @Test
    fun `the first disconnect requests a rebind`() {
        assertTrue(RebindGate(minIntervalMillis = 10_000).shouldRequest(0L))
    }

    @Test
    fun `a burst of disconnects inside the window is refused`() {
        val gate = RebindGate(minIntervalMillis = 10_000)

        assertTrue(gate.shouldRequest(1_000L))
        assertFalse(gate.shouldRequest(1_001L))
        assertFalse(gate.shouldRequest(10_999L))
        assertTrue(gate.shouldRequest(11_000L))
        assertFalse(gate.shouldRequest(11_500L))
    }

    @Test
    fun `a zero window always allows`() {
        val gate = RebindGate(minIntervalMillis = 0)
        assertTrue(gate.shouldRequest(5L))
        assertTrue(gate.shouldRequest(5L))
    }

    @Test
    fun `the elapsed clock may start anywhere`() {
        val gate = RebindGate(minIntervalMillis = 1_000)
        assertTrue(gate.shouldRequest(Long.MAX_VALUE - 500L))
        assertFalse(gate.shouldRequest(Long.MAX_VALUE))
    }
}
