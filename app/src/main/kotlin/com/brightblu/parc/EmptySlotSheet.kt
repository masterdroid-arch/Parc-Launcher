package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.*

/**
 * OPUS8 (T133): the empty-spot choices as a titled bottom sheet ("Empty spot on the outer arc"),
 * over a scrim that closes it, like Back. No stray "+ Cancel" row: a Close action sits in the title
 * row. Rows grow with the text size; the app list scrolls inside the sheet.
 */
@Composable
internal fun EmptySlotSheet(title: String, apps: List<AppEntry>, skin: Skin, picking: Boolean,
    onNewFolder: () -> Unit, onAddApp: () -> Unit, onPickApp: (AppId) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    Box(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt()))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(top = Metrics.keepOutTopDp.dp)
            .background(Color(skin.colors.surface.toInt()),
                RoundedCornerShape(topStart = Metrics.menuCornerDp.dp, topEnd = Metrics.menuCornerDp.dp))
            // Taps inside the sheet never reach the scrim.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .padding(bottom = Metrics.gridDp.dp * 2)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                .padding(start = Metrics.textGutterDp.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (picking) EditModeText.AddApp else title, Modifier.weight(1f).semantics { heading() },
                    style = sheetStyle(skin, skin.colors.textPrimary).copy(fontWeight = FontWeight.SemiBold))
                SheetAction(EditModeText.Close, skin, onClose)
            }
            if (picking) {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = (Metrics.touchTargetDp * PickerRows).dp)) {
                    items(sorted, key = { it.id.value }) { app ->
                        SheetAction(app.label, skin, { onPickApp(app.id) }, Modifier.fillMaxWidth())
                    }
                }
            } else {
                SheetAction(EditModeText.NewFolder, skin, onNewFolder, Modifier.fillMaxWidth())
                SheetAction(EditModeText.AddApp, skin, onAddApp, Modifier.fillMaxWidth())
            }
        }
    }
}

/** OPUS8 (T133): the edit-mode instruction bar. Display only: touches pass through to the ring. */
@Composable
internal fun EditModeBar(skin: Skin, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding()
        .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp), contentAlignment = Alignment.Center) {
        BasicText(EditModeText.Instructions,
            Modifier.background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
                RoundedCornerShape(Metrics.snackbarCornerDp.dp))
                .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp * 2),
            style = sheetStyle(skin, skin.colors.textPrimary).copy(fontSize = skin.typography.caption.sizeSp.sp))
    }
}

@Composable
private fun SheetAction(label: String, skin: Skin, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = Metrics.touchTargetDp.dp).clickable(onClick = onClick)
        .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp * 2),
        contentAlignment = Alignment.CenterStart) {
        BasicText(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = sheetStyle(skin, skin.colors.accent))
    }
}

private fun sheetStyle(skin: Skin, color: Long): TextStyle = TextStyle(
    fontFamily = if (skin.typography.fallbackFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else null,
    color = Color(color.toInt()), fontSize = skin.typography.menu.sizeSp.sp)

/** Rows of the app picker shown at once; the rest scroll. A count, not a size. */
private const val PickerRows = 6
