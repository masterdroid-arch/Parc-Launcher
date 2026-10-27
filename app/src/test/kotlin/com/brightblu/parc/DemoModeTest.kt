package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class DemoModeTest {
    @Test fun debugEmulatorOnly() {
        val fingerprint = "google/sdk_gphone64_arm64/emu64a:14/test"
        assertTrue(DemoMode.allowed(true, fingerprint, "sdk_gphone64_arm64", "ranchu"))
        assertFalse(DemoMode.allowed(false, fingerprint, "sdk_gphone64_arm64", "ranchu"))
        assertFalse(DemoMode.allowed(true, "Redmi/device/release", "Redmi Note 14", "mt6789"))
        assertFalse(DemoMode.allowed(true, fingerprint, "Redmi Note 14", "ranchu"))
        assertFalse(DemoMode.allowed(true, fingerprint, "sdk_gphone64_arm64", "mt6789"))
    }

    @Test fun demoPreferenceWritesCannotUseRealNames() {
        listOf("parc_ui", "crash_guard", "release_seen", "editor_hints").forEach {
            assertEquals("demo_$it", DemoMode.preferenceName(it))
            assertNotEquals(it, DemoMode.preferenceName(it))
        }
    }
}
