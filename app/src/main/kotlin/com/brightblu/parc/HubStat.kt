package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import com.brightblu.parc.model.readableAccent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/*
 * T322: the hub page type scale, written once and used by Today, Records, Steps and the rest.
 *   figure   28 sp  the skin's stat numeral (DotNumerals); the one big thing in a row
 *   heading  28 sp  a page or session heading
 *   section  17 sp  words where a row has no number, and card titles
 *   body     15 sp  values and the label beside a figure, so neither crushes the other
 *   caption  11 sp  quiet dates, units and hints, in a muted colour that stays readable on glass
 * A row is label over value, 56 dp at least, left aligned after a 24 dp icon column, with a thin divider between rows.
 */

/** textSecondary pulled toward textPrimary, so a label is never the faint thing on a see-through card. */
internal fun hubMuted(skin: Skin): Color =
    lerp(Color(skin.colors.textSecondary.toInt()), Color(skin.colors.textPrimary.toInt()), Metrics.hubMutedMix)

internal fun hubCaptionStyle(skin: Skin): TextStyle = hudLabelStyle(skin).copy(color = hubMuted(skin), fontWeight = FontWeight.Medium)

internal fun hubTitleStyle(skin: Skin, color: Long? = null): TextStyle =
    hudValueStyle(skin, color, HubType.section(skin.typography))

internal fun hubBodyStyle(skin: Skin, color: Long? = null): TextStyle =
    hudValueStyle(skin, color, HubType.body(skin.typography))

/** T322: a label in a figure-and-label pair. Body by default, or another role when the page asks. */
internal fun hubPairLabelStyle(skin: Skin, role: TypeToken? = null): TextStyle =
    hudValueStyle(skin, role = role ?: HubType.figureLabel(skin.typography)).copy(color = hubMuted(skin))

@Composable
internal fun HubDivider(skin: Skin, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(skin.shape.surfaceBorderDp.dp)
        .background(Color(skin.colors.lineBase.toInt())))
}

/** A quiet heading over a group of rows ("Personal bests", "Streaks"). */
@Composable
internal fun HubSectionLabel(text: String, skin: Skin, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(top = Metrics.hubSectionGapDp.dp).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = hubCaptionStyle(skin).copy(fontWeight = FontWeight.SemiBold))
}

/** `13,463 steps` becomes the figure `13,463` and the unit `steps`; anything else stays whole. */
internal object HubFigure {
    private val unitSplit = Regex("^(\\d[\\d,.]*)\\s+([A-Za-z]+)$")
    fun split(value: String): Pair<String, String?> =
        unitSplit.matchEntire(value)?.let { it.groupValues[1] to it.groupValues[2] } ?: (value to null)
}

/**
 * One row of a stats page. [label] is the small muted line; [value] is the figure (or, with [figure] false, a line of
 * words in the title size). [labelAside] sits quietly at the right of the label (a date), [side] at the right of the
 * value (a comparison) and is drawn in the accent colour when [sideAccent] is set. Nothing wraps into its neighbour:
 * the figure keeps its width and the right-hand text gives way.
 */
@Composable
internal fun HubStatRow(
    label: String,
    value: String,
    skin: Skin,
    modifier: Modifier = Modifier,
    icon: TodayIconKind? = null,
    figure: Boolean = true,
    labelAside: String? = null,
    side: String? = null,
    sideAccent: Boolean = false,
    sideAsCaption: Boolean = false,
    /** T322: a figure's label is body by default; pass caption for a quieter row. */
    labelRole: TypeToken? = null,
) {
    val labelStyle = hubPairLabelStyle(skin, labelRole)
    Row(modifier.fillMaxWidth().heightIn(min = Metrics.hubStatRowDp.dp).padding(vertical = Metrics.hubStatRowPadDp.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            TodayGlyph(icon, skin)
            Spacer(Modifier.width(Metrics.itemGapDp.dp * 1.5f))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.hubSectionGapDp.dp / 2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp), verticalAlignment = Alignment.Bottom) {
                if (label.isNotEmpty()) BasicText(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = labelStyle)
                if (labelAside != null) BasicText(labelAside, Modifier.widthIn(max = Metrics.hubStatSideMaxDp.dp * 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = hubCaptionStyle(skin).copy(color = hubMuted(skin).copy(alpha = 0.8f), textAlign = TextAlign.End))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp), verticalAlignment = Alignment.Bottom) {
                if (figure) {
                    val (number, unit) = remember(value) { HubFigure.split(value) }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        DotNumerals(number, DotRole.countDot, skin.typography, Color(skin.colors.textPrimary.toInt()),
                            Modifier.alignByBaseline(), useDot = isAllDot(number) && skin.typography.numeralFamily == "Doto")
                        if (unit != null) {
                            Spacer(Modifier.width(Metrics.lineGapDp.dp))
                            BasicText(unit, Modifier.alignByBaseline(), maxLines = 1, style = hubCaptionStyle(skin))
                        }
                    }
                } else BasicText(value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = hubTitleStyle(skin))
                if (side != null) BasicText(side, Modifier.widthIn(max = Metrics.hubStatSideMaxDp.dp).padding(bottom = Metrics.hubBaselineNudgeDp.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = (if (sideAsCaption) hubCaptionStyle(skin) else hubBodyStyle(skin, if (sideAccent) skin.colors.readableAccent else null))
                        .copy(textAlign = TextAlign.End))
            }
        }
    }
}

/** A drawn magnifier, 18 dp, in the muted colour. */
@Composable
internal fun SearchGlyph(skin: Skin, modifier: Modifier = Modifier) {
    val ink = hubMuted(skin)
    Canvas(modifier.size(Metrics.focusGlyphDp.dp)) {
        val w = Metrics.glyphStrokeDp.dp.toPx() * 1.2f
        val r = size.minDimension * 0.34f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(ink, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(ink, Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(size.width * 0.9f, size.height * 0.9f), w, StrokeCap.Round)
    }
}

/** A drawn sort sign (three bars shortening downward). */
@Composable
internal fun SortGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(Metrics.hubSortGlyphDp.dp)) {
        val w = Metrics.glyphStrokeDp.dp.toPx() * 1.3f
        val h = size.height
        listOf(0.25f to 1f, 0.5f to 0.68f, 0.75f to 0.36f).forEach { (y, len) ->
            drawLine(color, Offset(size.width * 0.08f, h * y), Offset(size.width * (0.08f + 0.84f * len), h * y), w, StrokeCap.Round)
        }
    }
}

/**
 * 1.5.1: the one slim search field of the hub (Commonplace, Notes): a single 36 dp line with a magnifier, a small
 * hint and a drawn cross to clear it. It is the same on every page so a search never takes more room than a row.
 */
@Composable
internal fun HubSearchField(query: String, hint: String, description: String, skin: Skin, modifier: Modifier = Modifier,
    enabled: Boolean = true, onQuery: (String) -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Metrics.hubSearchHeightDp.dp / 2)
    Row(modifier.fillMaxWidth().heightIn(min = Metrics.hubSearchHeightDp.dp)
        .background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), shape)
        .androidBorder(skin, shape)
        .padding(start = Metrics.hubSearchInsetDp.dp), verticalAlignment = Alignment.CenterVertically) {
        SearchGlyph(skin)
        Spacer(Modifier.width(Metrics.itemGapDp.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = onQuery, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            textStyle = hudValueStyle(skin, role = HubType.body(skin.typography)),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(skin.colors.accent.toInt())),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) BasicText(hint, maxLines = 1, style = hubPairLabelStyle(skin, HubType.body(skin.typography)))
                    inner()
                }
            })
        if (query.isNotEmpty()) {
            val ink = hubMuted(skin)
            Box(Modifier.size(Metrics.hubSearchHeightDp.dp).clickable(enabled = enabled, role = Role.Button) { onQuery("") }
                .semantics { contentDescription = "Clear search" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(Metrics.settingsIconDp.dp / 2f)) {
                    val w = Metrics.glyphStrokeDp.dp.toPx()
                    drawLine(ink, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
        } else Spacer(Modifier.width(Metrics.itemGapDp.dp))
    }
}

private fun Modifier.androidBorder(skin: Skin, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), shape))
