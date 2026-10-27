package com.brightblu.parc.core.data.power

import android.annotation.SuppressLint
import android.os.BatteryManager
import com.brightblu.parc.core.data.platform.PlatformLevel

/**
 * Cycle-count read with the Android 13 fallback (T66).
 *
 * `BatteryManager.EXTRA_CYCLE_COUNT` is an API 34 extra. Its key is a compile-time String, so
 * referencing it cannot crash on 33, but the key is not written there and the reading would only
 * ever be the caller's default. The SDK gate makes that explicit: below 34 [read] does not touch
 * the intent at all and returns null, which is the same "platform does not know" the UI already
 * renders as absent. [PowerMath.cycleCount] still drops the platform's -1 sentinel on 34.
 *
 * The reader lambda keeps this pure: a test can assert the intent is not even asked on 33.
 */
internal object BatteryCycleCount {

    @SuppressLint("InlinedApi") // Compile-time String key; only used when the SDK gate allows it.
    fun read(sdkInt: Int, extra: (String) -> Int): Int? {
        if (!PlatformLevel.hasBatteryCycleCount(sdkInt)) return null
        return PowerMath.cycleCount(extra(BatteryManager.EXTRA_CYCLE_COUNT))
    }
}
