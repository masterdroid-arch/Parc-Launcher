package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/**
 * Folder editor lists (T11, DESIGN §5 S10 for the ring half). Three pages: the folder list, the open
 * folder's slots with long-press drag, and the app picker. Colours and sizes come from [Skin] and
 * [Metrics]; the REJECT haptic on a refused drop belongs to the ring renderer (T12).
 *
 * The host owns Back and the ring; [FolderEditorScreen] is only the list surface.
 */
@Composable
fun FolderEditor(controller: FolderEditorController, skin: Skin, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val apps by controller.apps.collectAsState()
    FolderEditorScreen(state.toUi(apps), skin, controller::dispatch, modifier)
}

/** Stateless list surface. Pure input to output, so a host can drive it without the controller. */
@Composable
fun FolderEditorScreen(
    ui: FolderEditorUi,
    skin: Skin,
    onEvent: (FolderEditorEvent) -> Unit,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(color(skin.colors.bg))
            .padding(
                start = Metrics.textGutterDp.dp,
                end = Metrics.textGutterDp.dp,
                top = topPaddingDp.dp,
                bottom = Metrics.keepOutBottomDp.dp,
            ),
    ) {
        EditorScaffold(EditorSpec.Folders, skin, scroll = false,
            title = if (ui.page == EditorPage.Folders) EditorSpec.Folders.title else "Folder: ${ui.title}",
            instruction = when (ui.page) {
                EditorPage.Folders -> EditorSpec.Folders.instruction
                EditorPage.Contents -> "Tap Add apps or Remove. Hold and drag an app to Move it; changes save automatically."
                EditorPage.AppPicker -> "Tap an app to Add it to this folder; changes save automatically. Tap Back to return."
            },
            empty = ui.page == EditorPage.Folders && ui.folders.isEmpty(),
            undo = if (ui.canUndo) ({ onEvent(FolderEditorEvent.Undo) }) else null) {
        Header(ui, skin, onEvent)
        when (ui.page) {
            EditorPage.Folders -> FolderList(ui, skin, onEvent)
            EditorPage.Contents -> SlotList(ui, skin, onEvent)
            EditorPage.AppPicker -> AppPicker(ui, skin, onEvent)
        }
        NameField(ui, skin, onEvent)
        }
    }
}

@Composable
private fun Header(ui: FolderEditorUi, skin: Skin, onEvent: (FolderEditorEvent) -> Unit) {
    if (ui.page != EditorPage.Folders) {
        ActionText("Back to folders", skin, skin.colors.textSecondary) { onEvent(FolderEditorEvent.CloseFolder) }
    }
    ui.notice?.let { BasicText(it, style = style(skin.colors.textSecondary, skin.typography.caption)) }

}

@Composable
private fun ColumnScope.FolderList(ui: FolderEditorUi, skin: Skin, onEvent: (FolderEditorEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        item(key = "new-folder") {
            ListRow(
                label = "Add folder",
                detail = "",
                skin = skin,
                accent = true,
                onClick = { onEvent(FolderEditorEvent.BeginCreateFolder) },
            )
        }
        if (ui.folders.isEmpty()) {
            item(key = "empty") {
                BasicText(
                    "No folders yet",
                    style = style(skin.colors.textSecondary, skin.typography.caption),
                    modifier = Modifier.padding(vertical = Metrics.gridDp.dp),
                )
            }
        }
        items(ui.folders, key = { it.id.value }) { folder ->
            ListRow(
                label = folder.name,
                detail = "${folder.appCount}",
                skin = skin,
                accent = false,
                onClick = { onEvent(FolderEditorEvent.OpenFolder(folder.id)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.SlotList(ui: FolderEditorUi, skin: Skin, onEvent: (FolderEditorEvent) -> Unit) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { RowHeightDp.dp.toPx() }
    val centers = remember { mutableMapOf<Int, Float>() }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        item(key = "rename") {
            ListRow("Rename folder", "", skin, accent = false) { onEvent(FolderEditorEvent.BeginRename) }
        }
        item(key = "add") {
            ListRow(
                label = if (ui.folderIsFull) "Folder is full" else "Add apps",
                detail = "",
                skin = skin,
                accent = !ui.folderIsFull,
                onClick = if (ui.folderIsFull) ({}) else ({ onEvent(FolderEditorEvent.OpenAppPicker) }),
            )
        }
        items(ui.slots, key = { it.slot }) { row ->
            SlotRow(row, ui.slots.size, rowHeightPx, centers, skin, onEvent)
        }
    }
}

@Composable
private fun SlotRow(
    row: FolderSlotRow,
    capacity: Int,
    rowHeightPx: Float,
    centers: MutableMap<Int, Float>,
    skin: Skin,
    onEvent: (FolderEditorEvent) -> Unit,
) {
    DisposableEffect(row.slot) { onDispose { centers.remove(row.slot) } }
    val dragged = row.appId != null
    val border = when {
        row.refusesDrop -> skin.colors.danger
        row.dropTarget -> skin.colors.accent
        else -> skin.colors.lineBase
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeightDp.dp)
            .onGloballyPositioned { centers[row.slot] = it.positionInRoot().y + it.size.height / 2f }
            .padding(vertical = Metrics.gridDp.dp / 2f)
            .background(
                color(if (row.dropTarget) skin.colors.surface else skin.colors.bg),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .border(
                skin.shape.surfaceBorderDp.dp,
                color(border),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .then(
                if (dragged) {
                    Modifier.pointerInput(row.slot, capacity) {
                        var travelled = 0f
                        var origin = 0f
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                travelled = 0f
                                origin = centers[row.slot] ?: 0f
                                onEvent(FolderEditorEvent.DragStart(row.slot))
                            },
                            onDrag = { _, amount ->
                                travelled += amount.y
                                val slot = centers.minByOrNull { kotlin.math.abs(it.value - origin - travelled) }?.key
                                    ?: FolderDrag.dropTargetSlot(row.slot, travelled, rowHeightPx, capacity)
                                onEvent(FolderEditorEvent.DragTo(slot))
                            },
                            onDragEnd = { onEvent(FolderEditorEvent.Drop) },
                            onDragCancel = { onEvent(FolderEditorEvent.DragCancel) },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Metrics.gridDp.dp * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label(row),
            style = style(
                when {
                    row.appId == null -> skin.colors.textSecondary
                    row.suspended -> skin.colors.textSecondary
                    else -> skin.colors.textPrimary
                },
                skin.typography.menu,
            ),
            modifier = Modifier.weight(1f),
        )
        if (row.pinned) {
            BasicText("pin", style = style(skin.colors.accent, skin.typography.caption))
        }
        if (row.appId != null) {
            ActionText("Remove", skin, skin.colors.textSecondary) {
                onEvent(FolderEditorEvent.RemoveApp(row.appId))
            }
        }
    }
}

@Composable
private fun ColumnScope.AppPicker(ui: FolderEditorUi, skin: Skin, onEvent: (FolderEditorEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(ui.picker, key = { it.appId.value }) { app ->
            ListRow(
                label = app.label,
                detail = if (app.inFolder) "Added" else "",
                skin = skin,
                accent = !app.inFolder && !app.suspended,
                onClick = if (app.inFolder || app.suspended) {
                    ({})
                } else {
                    ({ onEvent(FolderEditorEvent.AddApp(app.appId)) })
                },
            )
        }
    }
}

@Composable
private fun ListRow(
    label: String,
    detail: String,
    skin: Skin,
    accent: Boolean,
    onClick: () -> Unit,
) {
    SettingsCard(skin) {
        SettingRow(label, skin, if (accent) SettingKind.Action else SettingKind.Page,
            description = detail, icon = "+", onClick = onClick)
    }
}

@Composable
private fun ActionText(text: String, skin: Skin, tint: Long, onClick: () -> Unit) {
    BasicText(
        text,
        style = style(tint, skin.typography.caption),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.gridDp.dp * 2, vertical = Metrics.gridDp.dp),
    )
}

@Composable
private fun NameField(ui: FolderEditorUi, skin: Skin, onEvent: (FolderEditorEvent) -> Unit) {
    val prompt = ui.prompt ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt.title) { focus.requestFocus() }
    LaunchedEffect(prompt.draft) {
        if (!prompt.isCreate) {
            kotlinx.coroutines.delay(Metrics.focusSaveDebounceMs)
            onEvent(FolderEditorEvent.ApplyNameDraft)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color(skin.colors.surface), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(
                skin.shape.surfaceBorderDp.dp,
                color(skin.colors.lineBase),
                RoundedCornerShape(Metrics.menuCornerDp.dp),
            )
            .padding(Metrics.gridDp.dp * 2),
    ) {
        BasicText(prompt.title, style = style(skin.colors.textSecondary, skin.typography.caption))
        BasicTextField(
            value = prompt.draft,
            onValueChange = { onEvent(FolderEditorEvent.NameChanged(it)) },
            singleLine = true,
            textStyle = style(skin.colors.textPrimary, skin.typography.menu),
            cursorBrush = SolidColor(color(skin.colors.accent)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(FolderEditorEvent.ConfirmName) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .padding(vertical = Metrics.gridDp.dp),
        )
        val issue = prompt.issue
        BasicText(
            issue ?: "Up to ${Metrics.folderLabelMaxChars} characters",
            style = style(
                if (issue == null) skin.colors.textSecondary else skin.colors.danger,
                skin.typography.caption,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (prompt.isCreate) ActionText("Cancel", skin, skin.colors.textSecondary) { onEvent(FolderEditorEvent.CancelName) }
            ActionText(prompt.confirmLabel, skin, skin.colors.accent) { onEvent(FolderEditorEvent.ConfirmName) }
        }
    }
}

/** One row of the editor. Height is the touch target; rows are the only tap surface (DESIGN §6). */
private val RowHeightDp = Metrics.touchTargetDp

private fun label(row: FolderSlotRow): String = when {
    row.appId == null -> "Empty slot"
    row.unknown -> "Not installed"
    else -> row.label
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(tint: Long, token: TypeToken): TextStyle = TextStyle(
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
