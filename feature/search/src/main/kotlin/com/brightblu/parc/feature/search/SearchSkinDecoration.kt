package com.brightblu.parc.feature.search

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.BackgroundPattern
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlin.math.sin

/** Static contour paths prepared for the search viewport; text/results keep opaque backing. */
internal fun Modifier.searchSkinDecoration(skin: Skin): Modifier = drawWithCache {
    val contours = Path()
    if (skin.style.background == BackgroundPattern.contour) {
        val spacing = Metrics.atlasContourSpacingDp.dp.toPx()
        val step = Metrics.gridDp.dp.toPx()
        val amplitude = Metrics.atlasContourAmplitudeDp.dp.toPx()
        var y = spacing
        while (y < size.height) {
            var x = 0f
            while (x <= size.width + step) {
                val yy = y + sin(x / spacing + y / amplitude) * amplitude
                if (x == 0f) contours.moveTo(x, yy) else contours.lineTo(x, yy)
                x += step
            }
            y += spacing
        }
    }
    val ink = Color(skin.colors.lineBase.toInt()).copy(alpha = Metrics.atlasPatternAlpha)
    val stroke = Stroke(skin.lineWidths.baseDp.dp.toPx())
    onDrawBehind { drawPath(contours, ink, style = stroke) }
}
