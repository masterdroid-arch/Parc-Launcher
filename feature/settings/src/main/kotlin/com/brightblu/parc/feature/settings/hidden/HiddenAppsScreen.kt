package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.ParcPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/**
 * Hidden apps screen (T17, F-17; T219 sections): the Hidden section at the top is permanent hide
 * only, with an Unhide per app and an Unhide all that clears nothing else. The All apps section
 * keeps the search and toggles and never repeats a Hidden row.
 *
 * Colours and sizes come only from [Skin] and [Metrics]. No icon bitmaps are requested, because a
 * row only needs the label. The scope line is the one short Visibility explanation, not a second
 * explainer screen.
 */
@Composable
fun HiddenApps(controller: HiddenAppsController, skin: Skin, modifier: Modifier = Modifier, topPaddingDp: Float = Metrics.keepOutTopDp) {
    val state by controller.state.collectAsState()
    HiddenAppsScreen(
        state = state,
        skin = skin,
        onQuery = controller::setQuery,
        onSetHidden = controller::setHidden,
        onUnhideAll = controller::unhideAll,
        modifier = modifier,
        topPaddingDp = topPaddingDp,
    )
}

/** Stateless surface; a preview or a host can drive it without the controller. */
@Composable
fun HiddenAppsScreen(
    state: HiddenAppsUiState,
    skin: Skin,
    onQuery: (String) -> Unit,
    onSetHidden: (AppId, Boolean) -> Unit,
    onUnhideAll: () -> Unit = {},
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
) {
    val undo = rememberEditorUndo()
    var confirmUnhideAll by remember { mutableStateOf(false) }
    if (confirmUnhideAll) AlertDialog(
        onDismissRequest = { confirmUnhideAll = false },
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText("Unhide all apps?", style = settingsText(skin)) },
        text = {
            BasicText(
                "This clears the permanent Hidden flag on every app. Focus, Lock and hidden folders are not changed.",
                style = settingsText(skin, skin.colors.textSecondary, true),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirmUnhideAll = false; onUnhideAll() }) {
                BasicText("Unhide all", style = settingsText(skin, skin.colors.accent))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmUnhideAll = false }) {
                BasicText("Cancel", style = settingsText(skin, skin.colors.textSecondary))
            }
        },
    )
    ParcPage(modifier.background(color(skin.colors.bg)).padding(top = topPaddingDp.dp), safeTop = false, scroll = false) {
        EditorScaffold(EditorSpec.HiddenApps, skin, title = "Hidden apps", instruction = "Switch an app to hide it.",
            scroll = false, empty = state.rows.isEmpty() && state.hiddenCount == 0,
            undo = if (undo.action != null) ({ undo.run() }) else null) {
        BasicText(
            if (state.hiddenCount == 1) "1 app hidden" else "${state.hiddenCount} apps hidden",
            style = style(skin, skin.colors.textSecondary, skin.typography.caption),
        )
        QueryField(state.query, skin, onQuery)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
            item(key = "hidden-section") {
                HiddenSection(
                    state = state,
                    skin = skin,
                    onUnhide = { id ->
                        onSetHidden(id, false)
                        undo.offer { onSetHidden(id, true) }
                    },
                    onUnhideAll = { confirmUnhideAll = true },
                )
            }
            item(key = "all-apps-header") {
                SettingsSection("All apps", skin)
            }
            items(state.visibleRows, key = { "visible-${it.appId.value}" }) { row ->
                AppRow(row, skin) { id, hidden ->
                    onSetHidden(id, hidden)
                    if (hidden) undo.offer { onSetHidden(id, false) }
                }
            }
        }
        }
    }
}

@Composable
private fun HiddenSection(
    state: HiddenAppsUiState,
    skin: Skin,
    onUnhide: (AppId) -> Unit,
    onUnhideAll: () -> Unit,
) {
    SettingsSection(
        if (state.hiddenCount == 1) "Hidden · 1" else "Hidden · ${state.hiddenCount}",
        skin,
    )
    when {
        state.hiddenCount == 0 -> BasicText(
            "Nothing hidden.",
            style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp),
        )
        state.hiddenRows.isEmpty() -> BasicText(
            "No hidden apps match this search.",
            style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp),
        )
        else -> SettingsCard(skin) {
            state.hiddenRows.forEachIndexed { index, row ->
                SettingRow(
                    title = row.label,
                    skin = skin,
                    kind = SettingKind.Action,
                    description = "Unhide",
                    divider = index != state.hiddenRows.lastIndex,
                ) { onUnhide(row.appId) }
            }
        }
    }
    if (state.hiddenCount > 0) {
        SettingsCard(skin) {
            SettingRow(
                title = "Unhide all",
                skin = skin,
                kind = SettingKind.Action,
                description = "Clear Hidden for every app. Focus, Lock and hidden folders stay as they are.",
                divider = false,
                onClick = onUnhideAll,
            )
        }
        BasicText(
            "Unhide all clears Hidden only. Focus, Lock and hidden folders are not changed.",
            style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(horizontal = Metrics.settingsRowInsetDp.dp),
        )
    }
}

@Composable
private fun QueryField(query: String, skin: Skin, onQuery: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style(skin, skin.colors.textPrimary, skin.typography.menu),
        cursorBrush = SolidColor(color(skin.colors.accent)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth().heightIn(min = Metrics.minTouchDp.dp)
            .padding(vertical = Metrics.sectionHeaderGapDp.dp),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                BasicText("Search apps", style = style(skin, skin.colors.textSecondary, skin.typography.menu))
            }
            inner()
        },
    )
}

@Composable
private fun AppRow(row: HiddenAppRow, skin: Skin, onSetHidden: (AppId, Boolean) -> Unit) {
    SettingsCard(skin) {
        SettingsTwoTargetRow(row.label, "Hide throughout Parc.", skin, row.hidden,
            onToggle = { onSetHidden(row.appId, it) }) {
            SettingsInfo(row.label, VisibilityCopy.Scope, skin)
        }
    }
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(skin: com.brightblu.parc.model.Skin, tint: Long, token: TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
