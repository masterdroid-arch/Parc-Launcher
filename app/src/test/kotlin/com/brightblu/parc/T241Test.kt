package com.brightblu.parc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T241: only a Home press while Parc is already in front sends the hub back to its first page. */
class T241Test {
    @Test fun aHomeIntentResetsOnlyWhenParcIsAlreadyInFront() {
        assertTrue(HomeKey.shouldResetOnHomeIntent(homeIntent = true, alreadyInFront = true))
        assertFalse(HomeKey.shouldResetOnHomeIntent(homeIntent = true, alreadyInFront = false))
        assertFalse(HomeKey.shouldResetOnHomeIntent(homeIntent = false, alreadyInFront = true))
        assertFalse(HomeKey.shouldResetOnHomeIntent(homeIntent = false, alreadyInFront = false))
    }
}
