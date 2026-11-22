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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * T312: the one shared "Add to arc" chooser. A quiet bottom sheet listing the arcs from the hub
 * outward, each named with how full it is. A tap on a non-full arc adds the app there; a tap on a
 * full arc says so in plain words and offers a shortcut to Settings > Home > Arcs.
 */
@Composable
internal fun ArcChooserSheet(
    appLabel: String,
    choices: List<ArcChoice>,
    skin: Skin,
    onPick: (ArcChoice) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var fullNotice by remember(choices) { mutableStateOf<String?>(null) }
    val allFull = choices.isNotEmpty() && choices.all { it.full }
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
            if (allFull) {
                ArcChooserMessage(ArcChooser.AllFullMessage, skin)
                ArcChooserSettingsRow(skin, onOpenSettings)
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)
                    .heightIn(max = (Metrics.touchTargetDp * ArcChooserRows).dp)) {
                    items(choices, key = { it.arcId }) { choice ->
                        Box(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                            .clickable(role = Role.Button) {
                                if (choice.full) fullNotice = ArcChooser.fullMessage(choice.name)
                                else onPick(choice)
                            }
                            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
                            contentAlignment = Alignment.CenterStart) {
                            BasicText(
                                choice.label,
                                style = TextStyle(
                                    fontFamily = skin.textFamily,
                                    color = Color(if (choice.full) skin.colors.textSecondary.toInt()
                                        else skin.colors.accent.toInt()),
                                    fontSize = skin.typography.menu.sizeSp.sp,
                                ),
                            )
                        }
                    }
                }
                fullNotice?.let { notice ->
                    ArcChooserMessage(notice, skin)
                    ArcChooserSettingsRow(skin, onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun ArcChooserMessage(text: String, skin: Skin) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
            ),
        )
    }
}

@Composable
private fun ArcChooserSettingsRow(skin: Skin, onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .clickable(role = Role.Button, onClick = onOpenSettings)
            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            "Settings",
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.accent.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rows of the arc chooser shown at once; a count, not a size. */
private const val ArcChooserRows = 6
