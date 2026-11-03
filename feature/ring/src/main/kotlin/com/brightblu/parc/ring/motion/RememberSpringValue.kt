package com.brightblu.parc.ring.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext

/** Observed once per setting change, never queried during a frame. No settings are written. */
private class ObservedDurationScale : MotionDurationScale {
    override var scaleFactor: Float = 1f
}

/** Retains the value and unregisters the system observer when its composition leaves. */
@Composable
fun rememberSpringValue(initialValue: Float): SpringValue {
    val resolver = LocalContext.current.contentResolver
    val scale = remember(resolver) { ObservedDurationScale() }
    DisposableEffect(resolver, scale) {
        fun update() {
            scale.scaleFactor = Settings.Global.getFloat(
                resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ).takeIf { it.isFinite() && it >= 0f } ?: 1f
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        update()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return remember(scale) { SpringValue(initialValue, scale) }
}
