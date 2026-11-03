package com.brightblu.parc.model

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * T157: a pressed row shrinks to [Metrics.rowPressScale] on `Springs.press` and springs back on release (DESIGN 2.6).
 * The animated value is read only inside the layer, so a press never recomposes the row. The system animator scale
 * 0 makes Compose finish the spring at once, so the row jumps.
 */
fun Modifier.parcPress(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed) Metrics.rowPressScale else 1f,
        spring(Springs.press.damping, Springs.press.stiffness), label = "row press")
    graphicsLayer {
        val s = scale.value
        scaleX = s; scaleY = s
    }
}
