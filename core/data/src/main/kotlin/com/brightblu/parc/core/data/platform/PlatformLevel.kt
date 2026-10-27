package com.brightblu.parc.core.data.platform

/**
 * T66: the API-level facts `:core:data` depends on, as pure predicates.
 *
 * The level is passed in rather than read from [android.os.Build] inside these functions so a JVM
 * test can exercise the Android 13 (33) fallback and the Android 14 (34) path with a fake level.
 * Production callers default to `Build.VERSION.SDK_INT`.
 */
internal object PlatformLevel {

    /** Android 14. `BatteryManager.EXTRA_CYCLE_COUNT` first exists here. */
    const val CycleCountApi = 34

    /**
     * True when the platform defines [android.os.BatteryManager.EXTRA_CYCLE_COUNT]. Below this the
     * extra key is never read; the cycle-count field stays absent instead of a guessed value.
     */
    fun hasBatteryCycleCount(sdkInt: Int): Boolean = sdkInt >= CycleCountApi
}
