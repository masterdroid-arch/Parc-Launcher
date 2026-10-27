package com.brightblu.parc.core.data.platform

import com.brightblu.parc.core.data.power.BatteryCycleCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T66: the Android 13 fallback for the one above-33 API in `:core:data`
 * (`BatteryManager.EXTRA_CYCLE_COUNT`, API 34). The level is injected, so both sides of the gate
 * run on the JVM; the lambda stands in for the sticky battery Intent.
 */
class PlatformLevelTest {

    @Test
    fun `android 13 never asks for the api 34 cycle-count extra`() {
        var askedForExtra = false

        val count = BatteryCycleCount.read(sdkInt = 33) {
            askedForExtra = true
            120
        }

        assertNull(count)
        assertFalse("the intent must not be read below API 34", askedForExtra)
    }

    @Test
    fun `android 14 reads the extra`() {
        assertEquals(120, BatteryCycleCount.read(sdkInt = 34) { 120 })
    }

    @Test
    fun `android 14 keeps the platform sentinel absent`() {
        assertNull(BatteryCycleCount.read(sdkInt = 34) { -1 })
    }

    @Test
    fun `a real zero cycle count is reported, not dropped`() {
        assertEquals(0, BatteryCycleCount.read(sdkInt = 34) { 0 })
    }

    @Test
    fun `newer levels keep reading the extra`() {
        assertEquals(7, BatteryCycleCount.read(sdkInt = 36) { 7 })
    }

    @Test
    fun `the gate opens exactly at 34`() {
        assertFalse(PlatformLevel.hasBatteryCycleCount(33))
        assertTrue(PlatformLevel.hasBatteryCycleCount(34))
    }
}
