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
import com.brightblu.parc.model.Skin

/** T249: the drawn source icons on the Today rows, in the same line style as the Settings group icons. */
internal enum class TodayIconKind {
    Agenda, Timer, Reminder, Weather, Steps, Sky, Countdown, Records, ScreenTime, Note, Book, More, Alarm, Zone;

    companion object {
        fun forPage(page: String): TodayIconKind = when (page) {
            "agenda" -> Agenda
            "timers" -> Timer
            "notes" -> Note
            "weather" -> Weather
            "steps" -> Steps
            "sky" -> Sky
            "year" -> Countdown
            "records" -> Records
            "apps" -> ScreenTime
            "library" -> Book
            else -> More
        }
    }
}

/** Decorative 24 dp icons; cached paths keep geometry allocation out of the draw phase. */
@Composable
internal fun TodayGlyph(kind: TodayIconKind, skin: Skin, modifier: Modifier = Modifier) {
    Spacer(modifier.size(Metrics.settingsIconDp.dp).clearAndSetSemantics {}.drawWithCache {
        val s = size.minDimension
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        val outline = Path()
        val fill = Path()
        val stroke = Stroke(Metrics.glyphStrokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tint = hubMuted(skin)
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            Rect(left + x * s, top + y * s, left + (x + w) * s, top + (y + h) * s)
        fun Path.move(x: Float, y: Float) = moveTo(left + x * s, top + y * s)
        fun Path.line(x: Float, y: Float) = lineTo(left + x * s, top + y * s)
        fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(left + x1 * s, top + y1 * s, left + x2 * s, top + y2 * s, left + x3 * s, top + y3 * s)
        with(outline) {
            when (kind) {
                TodayIconKind.Agenda -> {
                    addRoundRect(RoundRect(rect(.18f, .28f, .64f, .58f), CornerRadius(stroke.width)))
                    move(.18f, .42f); line(.82f, .42f)
                    move(.32f, .20f); line(.32f, .32f)
                    move(.68f, .20f); line(.68f, .32f)
                    fill.addOval(rect(.32f, .53f, .10f, .10f))
                }
                TodayIconKind.Timer -> {
                    addOval(rect(.20f, .26f, .60f, .60f))
                    move(.50f, .56f); line(.50f, .38f)
                    move(.50f, .56f); line(.66f, .64f)
                    move(.42f, .16f); line(.58f, .16f)
                    move(.50f, .16f); line(.50f, .26f)
                }
                TodayIconKind.Reminder -> {
                    move(.26f, .66f); line(.30f, .60f); line(.30f, .44f)
                    curve(.30f, .28f, .40f, .22f, .50f, .22f)
                    curve(.60f, .22f, .70f, .28f, .70f, .44f)
                    line(.70f, .60f); line(.74f, .66f); close()
                    addOval(rect(.41f, .68f, .18f, .12f))
                    move(.50f, .16f); line(.50f, .20f)
                }
                TodayIconKind.Weather -> {
                    addOval(rect(.22f, .18f, .28f, .28f))
                    move(.24f, .56f)
                    curve(.26f, .44f, .38f, .38f, .50f, .42f)
                    curve(.55f, .32f, .74f, .34f, .77f, .49f)
                    curve(.88f, .50f, .90f, .66f, .78f, .70f)
                    line(.28f, .70f)
                    curve(.16f, .68f, .16f, .56f, .24f, .56f)
                }
                TodayIconKind.Steps -> {
                    addOval(rect(.22f, .18f, .22f, .36f))
                    addOval(rect(.55f, .46f, .22f, .36f))
                    fill.addOval(rect(.26f, .58f, .12f, .12f))
                    fill.addOval(rect(.59f, .26f, .12f, .12f))
                }
                TodayIconKind.Sky -> {
                    move(.16f, .66f); line(.84f, .66f)
                    arcTo(rect(.24f, .26f, .52f, .52f), 180f, 180f, false)
                    move(.50f, .16f); line(.50f, .22f)
                    move(.18f, .30f); line(.23f, .35f)
                    move(.82f, .30f); line(.77f, .35f)
                }
                TodayIconKind.Countdown -> {
                    move(.28f, .18f); line(.72f, .18f)
                    move(.28f, .82f); line(.72f, .82f)
                    move(.32f, .18f); line(.32f, .30f)
                    curve(.32f, .44f, .44f, .46f, .50f, .50f)
                    curve(.56f, .54f, .68f, .56f, .68f, .70f)
                    move(.68f, .18f); line(.68f, .30f)
                    curve(.68f, .44f, .56f, .46f, .50f, .50f)
                    curve(.44f, .54f, .32f, .56f, .32f, .70f)
                    line(.32f, .82f); line(.68f, .82f)
                }
                TodayIconKind.Records -> {
                    addRoundRect(RoundRect(rect(.18f, .20f, .64f, .62f), CornerRadius(stroke.width)))
                    move(.18f, .38f); line(.82f, .38f)
                    fill.addOval(rect(.33f, .50f, .10f, .10f))
                    fill.addOval(rect(.53f, .50f, .10f, .10f))
                    fill.addOval(rect(.33f, .66f, .10f, .10f))
                }
                TodayIconKind.ScreenTime -> {
                    addRoundRect(RoundRect(rect(.28f, .16f, .44f, .66f), CornerRadius(stroke.width)))
                    move(.42f, .72f); line(.58f, .72f)
                    fill.addOval(rect(.46f, .68f, .08f, .08f))
                }
                TodayIconKind.Note -> {
                    move(.26f, .18f); line(.60f, .18f); line(.74f, .32f); line(.74f, .82f); line(.26f, .82f); close()
                    move(.60f, .18f); line(.60f, .32f); line(.74f, .32f)
                    move(.36f, .48f); line(.64f, .48f)
                    move(.36f, .62f); line(.56f, .62f)
                }
                TodayIconKind.Book -> {
                    move(.18f, .28f)
                    curve(.30f, .22f, .40f, .24f, .50f, .32f)
                    curve(.60f, .24f, .70f, .22f, .82f, .28f)
                    line(.82f, .74f)
                    curve(.70f, .68f, .60f, .70f, .50f, .78f)
                    curve(.40f, .70f, .30f, .68f, .18f, .74f)
                    close()
                    move(.50f, .32f); line(.50f, .78f)
                }
                TodayIconKind.Alarm -> {
                    addOval(rect(.22f, .28f, .56f, .56f))
                    move(.50f, .56f); line(.50f, .40f)
                    move(.50f, .56f); line(.62f, .62f)
                    move(.20f, .30f); line(.32f, .18f)
                    move(.80f, .30f); line(.68f, .18f)
                }
                TodayIconKind.Zone -> {
                    addOval(rect(.20f, .20f, .60f, .60f))
                    addOval(rect(.38f, .20f, .24f, .60f))
                    move(.20f, .50f); line(.80f, .50f)
                }
                TodayIconKind.More -> {
                    addOval(rect(.24f, .24f, .52f, .52f))
                    fill.addOval(rect(.44f, .44f, .12f, .12f))
                }
            }
        }
        onDrawBehind {
            drawPath(outline, tint, style = stroke)
            drawPath(fill, tint)
        }
    })
}
