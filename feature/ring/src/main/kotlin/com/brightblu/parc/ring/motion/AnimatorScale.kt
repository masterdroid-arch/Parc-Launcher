package com.brightblu.parc.ring.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The system animator duration scale, observed (never written). 0 means every spring jumps to its target. */
class AnimatorScale internal constructor() {
    @Volatile var value: Float = 1f
        internal set
}

@Composable
fun rememberAnimatorScale(): AnimatorScale {
    val resolver = LocalContext.current.contentResolver
    val scale = remember(resolver) { AnimatorScale() }
    DisposableEffect(resolver, scale) {
        fun update() {
            scale.value = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                .takeIf { it.isFinite() && it >= 0f } ?: 1f
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        update()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}
