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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.module.frostedBackground

/** T247: the app a search long-press menu acts on. [canUninstall] is computed by [AppActions]. */
internal data class SearchAppTarget(
    val appId: AppId,
    val label: String,
    val canUninstall: Boolean,
    /** T259: the alias the user already gave this app, shown in the sheet and edited by its own row. */
    val alias: String = "",
    /** T313: true when the app already sits on a Home arc; the row reads "Move to arc". */
    val placed: Boolean = false,
)

/** T247: one row of the search app menu, in the card's order. */
internal enum class SearchAppAction(val label: String) {
    AddToArc("Add to arc"),
    AddToFolder("Add to folder"),
    AppInfo("App info"),
    SetAlias("Set alias"),
    Uninstall("Uninstall"),
    Hide("Hide"),
}

/** T247: pure row rules and folder filtering for the search long-press menu. */
internal object SearchAppMenu {
    /** The five rows, with Uninstall left out when the platform cannot remove the app. */
    fun actions(canUninstall: Boolean): List<SearchAppAction> = buildList {
        add(SearchAppAction.AddToArc)
        add(SearchAppAction.AddToFolder)
        add(SearchAppAction.AppInfo)
        add(SearchAppAction.SetAlias)
        if (canUninstall) add(SearchAppAction.Uninstall)
        add(SearchAppAction.Hide)
    }

    /** Hidden folders are not offered, so a search action cannot reveal one. */
    fun folders(all: List<Folder>, hiddenFolderIds: Set<FolderId>): List<Folder> =
        all.filter { it.id !in hiddenFolderIds }
}

/**
 * T247: the app actions opened by a long press on a search row. It is a quiet bottom sheet, the
 * same surface as the existing empty-spot and new-app pickers. "Add to folder" turns the sheet
 * into its folder list; Back returns to the actions.
 */
@Composable
internal fun SearchAppMenuSheet(
    target: SearchAppTarget,
    folders: List<Folder>,
    skin: Skin,
    onAddToArc: () -> Unit,
    onAddToFolder: (Folder) -> Unit,
    onAppInfo: () -> Unit,
    /** T259: opens the alias editor, prefilled with [SearchAppTarget.alias]. */
    onSetAlias: () -> Unit,
    onUninstall: () -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    var choosingFolder by remember(target.appId) { mutableStateOf(false) }
    BackHandler {
        if (choosingFolder) choosingFolder = false else onClose()
    }
    Box(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt()))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(top = Metrics.keepOutTopDp.dp)
                .searchActionSurface(skin)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .imePadding()
                .padding(bottom = rememberPageInsets().bottomDp.dp),
        ) {
            SearchActionHeader(
                title = if (choosingFolder) "Add to folder" else target.label,
                detail = when {
                    choosingFolder -> "Choose a folder"
                    target.alias.isNotBlank() -> "Alias: ${target.alias}"
                    else -> ""
                },
                skin = skin,
                onClose = onClose,
            )
            if (!choosingFolder) {
                SearchAppMenu.actions(target.canUninstall).forEach { action ->
                    SearchActionRow(
                        if (action == SearchAppAction.AddToArc) addToArcLabel(target.placed) else action.label,
                        skin,
                        danger = action == SearchAppAction.Uninstall,
                    ) {
                        when (action) {
                            SearchAppAction.AddToArc -> onAddToArc()
                            SearchAppAction.AddToFolder -> choosingFolder = true
                            SearchAppAction.AppInfo -> onAppInfo()
                            SearchAppAction.SetAlias -> onSetAlias()
                            SearchAppAction.Uninstall -> onUninstall()
                            SearchAppAction.Hide -> onHide()
                        }
                    }
                }
            } else {
                if (folders.isEmpty()) {
                    SearchActionMessage("No folders yet", skin)
                } else {
                    LazyColumn(Modifier.fillMaxWidth()
                        .heightIn(max = (Metrics.menuRowHeightDp * SearchMenuVisibleRows).dp)) {
                        items(folders, key = { it.id.value }) { folder ->
                            SearchActionRow(folder.name, skin) { onAddToFolder(folder) }
                        }
                    }
                }
                SearchActionRow("Back", skin) { choosingFolder = false }
            }
        }
    }
}

@Composable
private fun SearchActionHeader(title: String, detail: String, skin: Skin, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.touchTargetDp.dp)
            .padding(start = Metrics.screenGutterDp.dp, end = Metrics.actionEdgeGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                Modifier.semantics { heading() },
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.textPrimary.toInt()),
                    fontSize = skin.typography.menu.sizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                BasicText(
                    detail,
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                    ),
                )
            }
        }
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
}

@Composable
private fun SearchActionRow(label: String, skin: Skin, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            label,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color((if (danger) skin.colors.danger else skin.colors.textPrimary).toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.menu.weight),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchActionMessage(text: String, skin: Skin) {
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

private fun Modifier.searchActionSurface(skin: Skin): Modifier = then(
    if (skin.shape.moduleStyle == com.brightblu.parc.model.ModuleStyle.frosted) {
        Modifier.frostedBackground(skin, Metrics.menuCornerDp, roundBottom = false)
    } else {
        Modifier.background(
            Color(skin.colors.surface.toInt()),
            RoundedCornerShape(topStart = Metrics.menuCornerDp.dp, topEnd = Metrics.menuCornerDp.dp),
        )
    },
)

/** Folder rows shown at once in the "Add to folder" page; a count, not a size. */
private const val SearchMenuVisibleRows = 6
