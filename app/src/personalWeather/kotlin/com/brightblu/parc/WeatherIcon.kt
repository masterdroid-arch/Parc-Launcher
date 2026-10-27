package com.brightblu.parc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Skin
import kotlin.math.cos
import kotlin.math.sin

/**
 * T137: the condition icon, drawn in code from the skin's colours. All shapes are built once per
 * size in the cache block; the draw block only paints them (no allocation while drawing).
 */
@Composable
internal fun WeatherIcon(kind: WeatherKind, isDay: Boolean, sizeDp: Float, skin: Skin) {
    val sun = Color(skin.colors.accent.toInt())
    val cloud = Color(skin.colors.textSecondary.toInt())
    val drop = Color(skin.colors.accent.toInt())
    val bolt = Color(skin.colors.warn.toInt())
    val modifier = remember(kind, isDay, sun, cloud, drop, bolt) {
        Modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = (w / 18f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
            val hasCloud = kind != WeatherKind.Clear
            val withPrecip = kind in setOf(WeatherKind.Drizzle, WeatherKind.Rain, WeatherKind.Snow, WeatherKind.Thunder)
            // The sun or moon: full size when clear, small and top-left behind a cloud.
            val small = kind == WeatherKind.PartlyCloudy
            val showSun = kind == WeatherKind.Clear || small
            val sunCenter = if (small) Offset(w * 0.36f, h * 0.34f) else Offset(w / 2f, h / 2f)
            val sunRadius = if (small) w * 0.17f else w * 0.22f
            val rays = FloatArray(32)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                val inner = sunRadius * 1.35f; val outer = sunRadius * 1.75f
                rays[i * 4] = sunCenter.x + (cos(a) * inner).toFloat(); rays[i * 4 + 1] = sunCenter.y + (sin(a) * inner).toFloat()
                rays[i * 4 + 2] = sunCenter.x + (cos(a) * outer).toFloat(); rays[i * 4 + 3] = sunCenter.y + (sin(a) * outer).toFloat()
            }
            val moon = Path().apply {
                op(Path().apply { addOval(Rect(sunCenter, sunRadius * 1.3f)) },
                    Path().apply { addOval(Rect(Offset(sunCenter.x + sunRadius * 0.7f, sunCenter.y - sunRadius * 0.5f), sunRadius * 1.15f)) },
                    PathOperation.Difference)
            }
            // A cloud: three circles on a rounded base, merged into one outline.
            val top = if (withPrecip) h * 0.16f else if (small) h * 0.34f else h * 0.26f
            val cloudH = h * 0.42f
            val left = if (small) w * 0.24f else w * 0.12f
            val right = w * 0.9f
            val cw = right - left
            val cloudPath = Path().apply {
                addRoundRect(RoundRect(Rect(left, top + cloudH * 0.45f, right, top + cloudH), CornerRadius(cloudH * 0.28f)))
                op(this, Path().apply { addOval(Rect(Offset(left + cw * 0.3f, top + cloudH * 0.52f), cloudH * 0.34f)) }, PathOperation.Union)
                op(this, Path().apply { addOval(Rect(Offset(left + cw * 0.58f, top + cloudH * 0.42f), cloudH * 0.44f)) }, PathOperation.Union)
                op(this, Path().apply { addOval(Rect(Offset(left + cw * 0.8f, top + cloudH * 0.62f), cloudH * 0.3f)) }, PathOperation.Union)
            }
            val below = top + cloudH + h * 0.06f
            val lines = FloatArray(12)
            val lineCount = if (kind == WeatherKind.Drizzle) 2 else 3
            for (i in 0 until 3) {
                val x = left + cw * (0.25f + 0.25f * i)
                lines[i * 4] = x; lines[i * 4 + 1] = below
                lines[i * 4 + 2] = x - w * 0.06f; lines[i * 4 + 3] = below + h * (if (kind == WeatherKind.Drizzle) 0.12f else 0.22f)
            }
            val flakes = FloatArray(6) { i -> if (i % 2 == 0) left + cw * (0.25f + 0.25f * (i / 2)) else below + h * 0.1f + (i / 2 % 2) * h * 0.1f }
            val boltPath = Path().apply {
                moveTo(w * 0.55f, below - h * 0.04f); lineTo(w * 0.42f, below + h * 0.16f); lineTo(w * 0.52f, below + h * 0.16f)
                lineTo(w * 0.44f, h * 0.98f); lineTo(w * 0.64f, below + h * 0.1f); lineTo(w * 0.54f, below + h * 0.1f); close()
            }
            val fog = FloatArray(12) { i ->
                val row = i / 4
                when (i % 4) { 0 -> w * (0.14f + 0.06f * (row % 2)); 2 -> w * (0.86f - 0.06f * ((row + 1) % 2)); else -> h * (0.36f + 0.16f * row) }
            }
            onDrawBehind {
                if (showSun) {
                    if (isDay) {
                        drawCircle(sun, sunRadius, sunCenter)
                        for (i in 0 until 8) drawLine(sun, Offset(rays[i * 4], rays[i * 4 + 1]), Offset(rays[i * 4 + 2], rays[i * 4 + 3]), stroke.width, StrokeCap.Round)
                    } else drawPath(moon, sun)
                }
                if (kind == WeatherKind.Fog) {
                    for (i in 0 until 3) drawLine(cloud, Offset(fog[i * 4], fog[i * 4 + 1]), Offset(fog[i * 4 + 2], fog[i * 4 + 3]), stroke.width * 1.4f, StrokeCap.Round)
                } else if (hasCloud) {
                    drawPath(cloudPath, cloud, alpha = 0.35f)
                    drawPath(cloudPath, cloud, style = stroke)
                }
                when (kind) {
                    WeatherKind.Drizzle, WeatherKind.Rain -> for (i in 0 until lineCount) {
                        drawLine(drop, Offset(lines[i * 4], lines[i * 4 + 1]), Offset(lines[i * 4 + 2], lines[i * 4 + 3]), stroke.width, StrokeCap.Round)
                    }
                    WeatherKind.Snow -> for (i in 0 until 3) drawCircle(drop, stroke.width * 1.1f, Offset(flakes[i * 2], flakes[i * 2 + 1]))
                    WeatherKind.Thunder -> drawPath(boltPath, bolt)
                    else -> Unit
                }
            }
        }
    }
    Box(Modifier.size(sizeDp.dp).then(modifier))
}
