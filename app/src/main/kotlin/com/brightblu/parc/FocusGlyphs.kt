package com.brightblu.parc

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draw the stored symbol without sending it to a font. Focus JSON and backups keep their glyphs. */
@Composable
internal fun FocusModeIcon(
    glyph: String,
    tint: Long,
    modifier: Modifier = Modifier,
    sizeDp: Float = Metrics.focusGlyphDp,
) {
    Spacer(modifier.size(sizeDp.dp).clearAndSetSemantics {}.drawWithCache {
        val s = size.minDimension
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        val outline = Path()
        val fill = Path()
        val stroke = Stroke(Metrics.glyphStrokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = Color(tint.toInt())
        fun Path.move(x: Float, y: Float) = moveTo(left + x * s, top + y * s)
        fun Path.line(x: Float, y: Float) = lineTo(left + x * s, top + y * s)
        fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(left + x1 * s, top + y1 * s, left + x2 * s, top + y2 * s, left + x3 * s, top + y3 * s)
        fun Path.circle(x: Float, y: Float, r: Float) =
            addOval(Rect(left + (x - r) * s, top + (y - r) * s, left + (x + r) * s, top + (y + r) * s))
        fun Path.box(x: Float, y: Float, w: Float, h: Float, r: Float) =
            addRoundRect(RoundRect(Rect(left + x * s, top + y * s, left + (x + w) * s, top + (y + h) * s), CornerRadius(r * s)))
        with(outline) {
            when (glyph) {
                "▣" -> {
                    box(.26f, .38f, .48f, .34f, .05f)
                    move(.40f, .38f); line(.40f, .30f); line(.60f, .30f); line(.60f, .38f)
                    move(.26f, .52f); line(.74f, .52f)
                }
                "◇" -> {
                    move(.50f, .22f); line(.78f, .50f); line(.50f, .78f); line(.22f, .50f); close()
                    move(.50f, .30f); line(.50f, .70f)
                }
                "☾" -> {
                    move(.62f, .20f)
                    curve(.34f, .22f, .22f, .46f, .30f, .66f)
                    curve(.38f, .84f, .62f, .88f, .72f, .74f)
                    curve(.52f, .76f, .40f, .58f, .46f, .40f)
                    curve(.50f, .30f, .56f, .24f, .62f, .20f)
                    close()
                }
                "→" -> {
                    move(.20f, .50f); line(.78f, .50f)
                    move(.62f, .36f); line(.78f, .50f); line(.62f, .64f)
                }
                "○" -> circle(.50f, .50f, .30f)
                "☆" -> {
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) .34f else .15f
                        val a = -PI / 2 + i * PI / 5
                        val x = .5f + (cos(a) * r).toFloat()
                        val y = .5f + (sin(a) * r).toFloat()
                        if (i == 0) move(x, y) else line(x, y)
                    }
                    close()
                }
                "♡" -> {
                    move(.50f, .78f)
                    curve(.16f, .56f, .20f, .24f, .38f, .28f)
                    curve(.46f, .30f, .50f, .38f, .50f, .38f)
                    curve(.50f, .38f, .54f, .30f, .62f, .28f)
                    curve(.80f, .24f, .84f, .56f, .50f, .78f)
                    close()
                }
                "△" -> { move(.50f, .22f); line(.80f, .76f); line(.20f, .76f); close() }
                "□" -> box(.24f, .24f, .52f, .52f, .04f)
                "☀" -> {
                    circle(.50f, .50f, .17f)
                    for (i in 0 until 8) {
                        val x = cos(i * PI / 4).toFloat()
                        val y = sin(i * PI / 4).toFloat()
                        move(.5f + x * .27f, .5f + y * .27f)
                        line(.5f + x * .38f, .5f + y * .38f)
                    }
                }
                "✿" -> {
                    for (i in 0 until 5) {
                        val a = -PI / 2 + i * 2 * PI / 5
                        circle(.5f + (cos(a) * .20f).toFloat(), .5f + (sin(a) * .20f).toFloat(), .12f)
                    }
                    fill.circle(.50f, .50f, .07f)
                }
                else -> fill.circle(.50f, .50f, .16f)
            }
        }
        onDrawBehind {
            drawPath(outline, color, style = stroke)
            drawPath(fill, color)
        }
    })
}
