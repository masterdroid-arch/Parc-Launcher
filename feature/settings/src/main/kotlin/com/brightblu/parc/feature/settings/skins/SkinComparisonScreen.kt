package com.brightblu.parc.feature.settings.skins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import com.brightblu.parc.model.drawingSpec
import com.brightblu.parc.model.ParcPage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure preview geometry for the skin comparison (S15). It takes no [Skin], so every skin draws its
 * arcs and slots at the same positions by construction: a skin is a token set, never a layout
 * (DESIGN §2.8). Kept pure and public so the equality is unit-testable without a device.
 */
object SkinPreviewGeometry {
    /** Arc radii as a fraction of the smaller side of the preview box. */
    val arcRadii: List<Float> = listOf(0.30f, 0.45f, 0.60f)

    /** Slots per arc, innermost first; slots are inset so none sits at an arc end. */
    val slotsPerArc: List<Int> = listOf(3, 4, 4)

    const val arcStartDegrees = 180f
    const val arcSweepDegrees = 90f

    /** One slot centre in polar terms, measured from the hub. Immutable, non-null. */
    data class Slot(val degrees: Float, val radiusFraction: Float)

    /** All preview slot centres, hub-outward. Identical for every skin. */
    fun slots(): List<Slot> = buildList {
        arcRadii.forEachIndexed { arcIndex, radius ->
            val count = slotsPerArc[arcIndex]
            for (slot in 0 until count) {
                val offset = (slot + 0.5f) / count
                add(Slot(arcStartDegrees + offset * arcSweepDegrees, radius))
            }
        }
    }
}

/**
 * Skin comparison (DESIGN §5 S15): two skins side by side, each drawn with its own colour tokens over
 * [SkinPreviewGeometry] (the same positions). Only colours change; geometry never does.
 */
@Composable
fun SkinComparisonScreen(skins: List<Skin>, skin: Skin, modifier: Modifier = Modifier) {
    ParcPage(modifier.background(color(skin.colors.bg)), scroll = false) {
        BasicText("Skin comparison", style = skinTokenStyle(skin, skin.colors.textPrimary, skin.typography.title))
        BasicText(
            "Same geometry, different tokens",
            style = skinTokenStyle(skin, skin.colors.textSecondary, skin.typography.caption),
            modifier = Modifier.padding(top = Metrics.lineGapDp.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp),
        ) {
            skins.take(2).forEach { candidate ->
                Column(Modifier.weight(1f)) {
                    BasicText(
                        candidate.name,
                        style = skinTokenStyle(skin, candidate.colors.accent, skin.typography.menu),
                    )
                    BasicText(
                        candidate.id,
                        style = skinTokenStyle(skin, skin.colors.textSecondary, skin.typography.caption),
                    )
                    MiniRing(candidate, Modifier.fillMaxWidth().height(180.dp))
                    Swatches(candidate)
                }
            }
        }
    }
}

/** Draws the fixed preview geometry with one skin's colours. */
@Composable
internal fun MiniRing(skin: Skin, modifier: Modifier) {
    val spec = androidx.compose.runtime.remember(skin) { skin.drawingSpec() }
    Box(modifier.background(color(skin.colors.bg)).then(Modifier.drawWithCache {
        val originX = size.width * 0.84f
        val originY = size.height * 0.86f
        val unit = min(size.width, size.height)
        val rails = androidx.compose.ui.graphics.Path()
        val ticks = androidx.compose.ui.graphics.Path()
        SkinPreviewGeometry.arcRadii.forEach { fraction ->
            val r = fraction * unit
            rails.addArc(androidx.compose.ui.geometry.Rect(originX - r, originY - r, originX + r, originY + r), 180f, 90f)
            if (spec.ticks) for (degree in 180..270 step Metrics.atlasTickStepDegrees) {
                val a = Math.toRadians(degree.toDouble())
                val x = cos(a).toFloat(); val y = sin(a).toFloat()
                ticks.moveTo(originX + x * r, originY + y * r)
                ticks.lineTo(originX + x * (r + Metrics.atlasTickDp.dp.toPx()), originY + y * (r + Metrics.atlasTickDp.dp.toPx()))
            }
        }
        val slots = SkinPreviewGeometry.slots().map {
            val a = Math.toRadians(it.degrees.toDouble()); val r = it.radiusFraction * unit
            Offset(originX + cos(a).toFloat() * r, originY + sin(a).toFloat() * r)
        }
        val frames = androidx.compose.ui.graphics.Path()
        if (spec.iconFrame == com.brightblu.parc.model.IconFrame.brackets) for (slot in slots) {
            val r = unit * 0.045f
            frames.moveTo(slot.x - r / 2, slot.y - r); frames.lineTo(slot.x - r, slot.y - r)
            frames.lineTo(slot.x - r, slot.y + r); frames.lineTo(slot.x - r / 2, slot.y + r)
            frames.moveTo(slot.x + r / 2, slot.y - r); frames.lineTo(slot.x + r, slot.y - r)
            frames.lineTo(slot.x + r, slot.y + r); frames.lineTo(slot.x + r / 2, slot.y + r)
        }
        val stroke = Stroke(skin.lineWidths.baseDp.dp.toPx(), pathEffect =
            if (spec.dashOnDp > 0f) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(spec.dashOnDp.dp.toPx(), spec.dashOffDp.dp.toPx())) else null)
        val tickStroke = Stroke(skin.lineWidths.baseDp.dp.toPx())
        val line = color(skin.colors.lineBase); val accent = color(skin.colors.accent)
        val origin = Offset(originX, originY)
        val radius = unit * 0.045f
        onDrawBehind {
            drawPath(rails, line, style = stroke); drawPath(ticks, line, style = tickStroke)
            if (spec.iconFrame == com.brightblu.parc.model.IconFrame.brackets) drawPath(frames, accent, style = tickStroke)
            else for (i in slots.indices) drawCircle(accent, radius, slots[i], style = tickStroke)
            drawCircle(accent, radius, origin, style = tickStroke)
            if (spec.hub != com.brightblu.parc.model.HubStyle.plain) {
                drawLine(accent, Offset(originX - radius, originY), Offset(originX + radius, originY))
                drawLine(accent, Offset(originX, originY - radius), Offset(originX, originY + radius))
            }
        }
    }))

}

@Composable
private fun Swatches(skin: Skin) {
    Row(
        Modifier.fillMaxWidth().padding(top = Metrics.itemGapDp.dp),
        horizontalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp),
    ) {
        listOf(
            skin.colors.lineBase,
            skin.colors.lineActive,
            skin.colors.accent,
            skin.colors.textPrimary,
            skin.colors.textSecondary,
            skin.colors.pulse,
            skin.colors.danger,
        ).forEach { token ->
            Box(
                Modifier
                    .size(Metrics.gridDp.dp * 4)
                    .background(color(token)),
            )
        }
    }
}
