package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.module.frostedBackground

/**
 * T246: the quiet one-line "Add <app> to an arc?" prompt. It is a single tappable row on the same
 * surface as the Undo chip; no bright fill, no second action.
 */
@Composable
internal fun NewAppPromptBar(label: String, skin: Skin, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = Metrics.snackbarBottomDp.dp), contentAlignment = Alignment.Center) {
        BasicText(
            NewAppPrompt.question(label),
            modifier = Modifier
                .background(
                    Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
                    RoundedCornerShape(Metrics.snackbarCornerDp.dp),
                )
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textPrimary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * T246: the free-spot picker opened by the prompt. A quiet bottom sheet, like the empty-spot sheet:
 * every row names its arc and one-based spot, and the list scrolls when the arcs are tall.
 */
@Composable
internal fun SpotPickerSheet(
    appLabel: String,
    spots: List<ArcSpotChoice>,
    skin: Skin,
    onPick: (ArcSpotChoice) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt()))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(top = Metrics.keepOutTopDp.dp)
            .then(
                if (skin.shape.moduleStyle == com.brightblu.parc.model.ModuleStyle.frosted)
                    Modifier.frostedBackground(skin, Metrics.menuCornerDp, roundBottom = false)
                else Modifier.background(
                    Color(skin.colors.surface.toInt()),
                    RoundedCornerShape(topStart = Metrics.menuCornerDp.dp, topEnd = Metrics.menuCornerDp.dp),
                )
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(bottom = rememberPageInsets().bottomDp.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                .padding(start = Metrics.screenGutterDp.dp, end = Metrics.actionEdgeGapDp.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "Add $appLabel to an arc",
                    Modifier.weight(1f).semantics { heading() },
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textPrimary.toInt()),
                        fontSize = skin.typography.menu.sizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Box(Modifier.clickable(onClick = onClose)
                    .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.itemGapDp.dp)) {
                    BasicText(
                        "Close",
                        style = TextStyle(
                            fontFamily = skin.textFamily,
                            color = Color(skin.colors.accent.toInt()),
                            fontSize = skin.typography.menu.sizeSp.sp,
                        ),
                    )
                }
            }
            if (spots.isEmpty()) {
                BasicText(
                    "No free spots",
                    Modifier.padding(horizontal = Metrics.screenGutterDp.dp,
                        vertical = Metrics.itemGapDp.dp),
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                    ),
                )
            } else {
                BasicText(
                    "Free spots",
                    Modifier.padding(horizontal = Metrics.screenGutterDp.dp),
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                    ),
                )
                LazyColumn(Modifier.weight(1f, fill = false)
                    .heightIn(max = (Metrics.touchTargetDp * SpotPickerRows).dp)) {
                    items(spots, key = { "${it.position.arcId}/${it.position.index}" }) { spot ->
                        Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                            .clickable { onPick(spot) }
                            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
                            contentAlignment = Alignment.CenterStart) {
                            BasicText(
                                spot.label,
                                style = TextStyle(
                                    fontFamily = skin.textFamily,
                                    color = Color(skin.colors.accent.toInt()),
                                    fontSize = skin.typography.menu.sizeSp.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rows of the spot picker shown at once; a count, not a size. */
private const val SpotPickerRows = 6
