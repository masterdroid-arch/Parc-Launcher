package com.brightblu.parc.core.data.lock

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** OPUS6 (audit F10): a listener that connected before the policy existed rebuilds when it arrives. */
class NotificationLockPolicyTest {
    @After fun reset() {
        NotificationLockPolicy.onReady = null
        NotificationLockPolicy.packages = null
    }

    @Test fun failsClosedUntilThePolicyArrivesThenAsksTheListenerToRebuildOnce() {
        NotificationLockPolicy.packages = null
        var rebuilds = 0
        NotificationLockPolicy.onReady = { rebuilds++ }
        assertFalse("nothing is read before the policy", NotificationLockPolicy.mayRead("chat"))

        NotificationLockPolicy.packages = { setOf("bank") }
        assertEquals(1, rebuilds)
        assertTrue(NotificationLockPolicy.mayRead("chat"))
        assertFalse(NotificationLockPolicy.mayRead("bank"))

        // Later policy updates refilter as before; they do not rebuild again.
        NotificationLockPolicy.packages = { emptySet() }
        assertEquals(1, rebuilds)
    }

    @Test fun noListenerMeansNothingToRebuild() {
        NotificationLockPolicy.packages = null
        NotificationLockPolicy.onReady = null
        NotificationLockPolicy.packages = { emptySet() }
        assertTrue(NotificationLockPolicy.mayRead("chat"))
    }
}
