package com.brightblu.parc.ring.module

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Springs

/** DESIGN 3.1: while the module is touched the rest of the screen dims to 70 % and moves 6 dp up and left. */
object ModuleLean {
    fun alpha(progress: Float): Float = 1f - (1f - Metrics.moduleLeanAlpha) * progress.coerceIn(0f, 1f)
    fun shiftDp(progress: Float): Float = -Metrics.moduleLeanDp * progress.coerceIn(0f, 1f)
}

/**
 * The module's touch progress, smoothed on `springSheet`. The target is [HubModuleState.touchProgress],
 * the only value the rest of the screen may read from the module. The returned lambda reads the animated value.
 */
@Composable
fun rememberLeanProgress(module: HubModuleState): () -> Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(module) {
        androidx.compose.runtime.snapshotFlow { module.touchProgress }.collect { target ->
            value.animateTo(target, spring(Springs.sheet.damping, Springs.sheet.stiffness))
        }
    }
    return { value.value }
}

/** Dim and lean for anything outside the module. Reads only [progress]; never the card colour or glow. */
fun Modifier.leanAway(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    alpha = ModuleLean.alpha(p)
    val shift = ModuleLean.shiftDp(p) * density
    translationX = shift
    translationY = shift
}
