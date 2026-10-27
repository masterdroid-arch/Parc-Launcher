package com.brightblu.parc.ring

import android.util.Log
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.*
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.*

/** Main-thread debug driver. Animated float values are observed only by the Canvas draw lambda. */
class SpikeController(
    private val skin: Skin,
    initialLevel: Float = 0f,
    val animatorScale: Float = 1f,
) {
    val level = mutableFloatStateOf(initialLevel)
    val breathe = mutableFloatStateOf(0f)
    val glowX = mutableFloatStateOf(0f)
    val glowY = mutableFloatStateOf(0f)
    val touching = mutableStateOf(false)
    val activeArc = mutableIntStateOf(-1)
    private val ease = skin.motion.easeEmphasized.let { CubicBezierEasing(it.x1, it.y1, it.x2, it.y2) }
    private var job: Job? = null
    private var target = initialLevel >= 0.5f

    fun interrupt() {
        job?.cancel()
        target = level.floatValue >= 0.5f
        level.floatValue = if (target) 1f else 0f
    }
    fun snap(scope: CoroutineScope) {
        job?.cancel()
        target = !target
        job = scope.launch { animate(if (target) 1f else 0f) }
    }
    fun loop(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            Log.i("Parc", "M0 loop start: 10 snaps, 320ms, 600ms gaps")
            repeat(SpikeConfig.loopSnaps) {
                target = !target
                animate(if (target) 1f else 0f)
                delay(SpikeConfig.gapMs)
            }
            Log.i("Parc", "M0 loop complete")
        }
    }
    private suspend fun animate(destination: Float) {
        if (animatorScale <= 0f) {
            level.floatValue = destination
            return
        }
        val from = level.floatValue
        val start = withFrameNanos { it }
        val durationNanos = (skin.motion.durSnap * animatorScale * 1_000_000L).toLong()
        do {
            val progress = withFrameNanos { ((it - start).toDouble() / durationNanos).toFloat() }
            level.floatValue = from + (destination - from) * ease.transform(progress.coerceIn(0f, 1f))
        } while (progress < 1f)
        level.floatValue = destination
    }
}
