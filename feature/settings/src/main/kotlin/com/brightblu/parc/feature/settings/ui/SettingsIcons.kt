package com.brightblu.parc.feature.settings.ui

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

/** T221: root group icons are drawings, never fallback font glyphs. */
enum class SettingsIcon {
    Home, Appearance, GesturesSearch, NotificationsFocus, DataAbout,
    /** T373: hub pages and the root shortcut tiles. */
    HubPages, Wallpaper, Glass, LeftHand, LockLayout,
    /** T375: the gear beside each hub page switch. */
    Gear,
}

/** Decorative 24 dp icons; cached paths keep geometry allocation out of the draw phase. */
@Composable
fun SettingsGroupGlyph(icon: SettingsIcon, skin: Skin, modifier: Modifier = Modifier) {
    Spacer(modifier.size(Metrics.settingsIconDp.dp).clearAndSetSemantics {}.drawWithCache {
        val s = size.minDimension
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        val outline = Path()
        val fill = Path()
        val stroke = Stroke(Metrics.glyphStrokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tint = Color(Skin.ParcOrange.toInt())
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            Rect(left + x * s, top + y * s, left + (x + w) * s, top + (y + h) * s)
        fun Path.move(x: Float, y: Float) = moveTo(left + x * s, top + y * s)
        fun Path.line(x: Float, y: Float) = lineTo(left + x * s, top + y * s)
        fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(left + x1 * s, top + y1 * s, left + x2 * s, top + y2 * s, left + x3 * s, top + y3 * s)
        with(outline) {
            when (icon) {
                SettingsIcon.Home -> {
                    arcTo(rect(.18f, .18f, 1.20f, 1.20f), 180f, 90f, true)
                    fill.addOval(rect(.71f, .71f, .14f, .14f))
                }
                SettingsIcon.Appearance -> {
                    addOval(rect(.34f, .34f, .32f, .32f))
                    move(.50f, .14f); line(.50f, .24f)
                    move(.50f, .76f); line(.50f, .86f)
                    move(.14f, .50f); line(.24f, .50f)
                    move(.76f, .50f); line(.86f, .50f)
                }
                SettingsIcon.GesturesSearch -> {
                    move(.18f, .72f)
                    curve(.20f, .42f, .40f, .25f, .76f, .28f)
                    move(.62f, .16f); line(.76f, .28f); line(.63f, .42f)
                }
                SettingsIcon.NotificationsFocus -> {
                    move(.26f, .64f); line(.30f, .58f); line(.30f, .44f)
                    curve(.30f, .28f, .40f, .22f, .50f, .22f)
                    curve(.60f, .22f, .70f, .28f, .70f, .44f)
                    line(.70f, .58f); line(.74f, .64f); close()
                    arcTo(rect(.41f, .64f, .18f, .14f), 20f, 140f, true)
                    move(.50f, .18f); line(.50f, .22f)
                }
                SettingsIcon.DataAbout -> {
                    addRoundRect(RoundRect(rect(.18f, .28f, .64f, .18f), CornerRadius(stroke.width)))
                    addRoundRect(RoundRect(rect(.22f, .46f, .56f, .32f), CornerRadius(stroke.width)))
                    move(.40f, .57f); line(.60f, .57f)
                }
                SettingsIcon.HubPages -> {
                    addRoundRect(RoundRect(rect(.16f, .20f, .68f, .60f), CornerRadius(stroke.width * 2f)))
                    move(.28f, .64f); line(.42f, .48f); line(.54f, .56f); line(.72f, .36f)
                }
                SettingsIcon.Wallpaper -> {
                    addRoundRect(RoundRect(rect(.16f, .20f, .68f, .60f), CornerRadius(stroke.width * 2f)))
                    move(.18f, .72f); line(.38f, .52f); line(.52f, .66f); line(.64f, .56f); line(.82f, .74f)
                    fill.addOval(rect(.62f, .30f, .12f, .12f))
                }
                SettingsIcon.Glass -> {
                    move(.50f, .18f); line(.82f, .36f); line(.50f, .54f); line(.18f, .36f); close()
                    move(.18f, .52f); line(.50f, .70f); line(.82f, .52f)
                    move(.18f, .68f); line(.50f, .86f); line(.82f, .68f)
                }
                SettingsIcon.LeftHand -> {
                    move(.24f, .20f); line(.24f, .80f)
                    move(.80f, .50f); line(.38f, .50f)
                    move(.54f, .34f); line(.38f, .50f); line(.54f, .66f)
                }
                SettingsIcon.Gear -> {
                    addOval(rect(.34f, .34f, .32f, .32f))
                    for (i in 0 until 8) {
                        val a = i * Math.PI / 4
                        val c = Math.cos(a).toFloat(); val d = Math.sin(a).toFloat()
                        move(.50f + c * .24f, .50f + d * .24f); line(.50f + c * .36f, .50f + d * .36f)
                    }
                }
                SettingsIcon.LockLayout -> {
                    addRoundRect(RoundRect(rect(.26f, .46f, .48f, .36f), CornerRadius(stroke.width * 2f)))
                    arcTo(rect(.34f, .16f, .32f, .60f), 180f, 180f, true)
                    fill.addOval(rect(.46f, .58f, .08f, .08f))
                }
            }
        }
        onDrawBehind {
            drawPath(outline, tint, style = stroke)
            drawPath(fill, tint)
        }
    })
}

/** The selected-option mark, drawn without a font. */
@Composable
fun SettingsCheckMark(skin: Skin, modifier: Modifier = Modifier) {
    Spacer(modifier.clearAndSetSemantics {}.drawWithCache {
        val stroke = Stroke(Metrics.glyphStrokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tint = Color(skin.colors.accent.toInt())
        val path = Path().apply {
            moveTo(size.width * .20f, size.height * .52f)
            lineTo(size.width * .42f, size.height * .74f)
            lineTo(size.width * .82f, size.height * .26f)
        }
        onDrawBehind { drawPath(path, tint, style = stroke) }
    })
}
