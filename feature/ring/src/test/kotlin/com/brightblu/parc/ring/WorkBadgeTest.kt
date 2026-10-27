package com.brightblu.parc.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T75: the badge rule is pure, so every combination is checked without a renderer. */
class WorkBadgeTest {
    @Test fun aWorkAppIsMarkedWhileMarkWorkAppsIsOn() {
        assertTrue(workBadgeVisible(isWorkApp = true, markWorkApps = true))
    }

    @Test fun turningMarkWorkAppsOffHidesTheBadgeEverywhere() {
        assertFalse(workBadgeVisible(isWorkApp = true, markWorkApps = false))
    }

    @Test fun aPersonalAppIsNeverMarked() {
        assertFalse(workBadgeVisible(isWorkApp = false, markWorkApps = true))
        assertFalse(workBadgeVisible(isWorkApp = false, markWorkApps = false))
    }
}
