package com.brightblu.parc.ring

import androidx.compose.animation.core.CubicBezierEasing
import com.brightblu.parc.model.Metrics

/** Raw normalized time keeps stagger delays at 18 ms, independent of the travel easing. */
internal object FolderTransition {
    private val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun emphasis(progress: Float): Float = ease.transform(progress.coerceIn(0f, 1f))

    fun slot(progress: Float, index: Int): Float {
        val delay = index.coerceIn(0, Metrics.folderStaggerMax - 1) * Metrics.folderStaggerMs
        val duration = Metrics.folderTransitionMs - delay
        val t = ((progress * Metrics.folderTransitionMs - delay) / duration).coerceIn(0f, 1f)
        if (t == 0f || t == 1f) return t
        val u = t - 1f
        return 1f + (Metrics.folderOvershoot + 1f) * u * u * u + Metrics.folderOvershoot * u * u
    }
}

/** Shared by the ring and the host overlay for the existing All-apps list. */
fun joystickBack(dxDp: Float, dyDp: Float): Boolean = RingAim.isBack(dxDp, dyDp)
