package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Test

class T377Test {

    /** One scale per unit: each reading is a fraction of the shared scale, never its own maximum. */
    @Test fun sharedBarFractionUsesOneScalePerUnit() {
        // Wi-Fi 420 MB and mobile 180 MB out of a 600 MB day total.
        assertEquals(0.7f, sharedBarFraction(420_000_000L, 600_000_000L), 0.0001f)
        assertEquals(0.3f, sharedBarFraction(180_000_000L, 600_000_000L), 0.0001f)
        // Storage 42 of 128 GB.
        assertEquals(42f / 128f, sharedBarFraction(42L, 128L), 0.0001f)
    }

    @Test fun sharedBarFractionClampsAndHandlesMissingReadings() {
        assertEquals(0f, sharedBarFraction(null, 600L), 0f)
        assertEquals(0f, sharedBarFraction(100L, null), 0f)
        assertEquals(0f, sharedBarFraction(100L, 0L), 0f)
        assertEquals(0f, sharedBarFraction(100L, -1L), 0f)
        assertEquals(0f, sharedBarFraction(-5L, 100L), 0f)
        assertEquals(1f, sharedBarFraction(100L, 50L), 0f)
    }
}
