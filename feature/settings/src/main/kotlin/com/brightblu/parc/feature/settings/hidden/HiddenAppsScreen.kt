package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.ParcPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.brightblu.parc.feature.settings.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
 * Hidden apps screen (T17, F-17): find an app, hide it or bring it back. Search, the ABC list, the
 * folder picker and the ring already leave hidden apps out, so this screen is the only missing half.
 *
 * Colours and sizes come only from [Skin] and [Metrics]. The list is a single [LazyColumn]; no icon
 * bitmaps are requested, because a row only needs the label.
 */
@Composable
fun HiddenApps(controller: HiddenAppsController, skin: Skin, modifier: Modifier = Modifier, topPaddingDp: Float = Metrics.keepOutTopDp) {
    val state by controller.state.collectAsState()
    HiddenAppsScreen(
        state = state,
        skin = skin,
        onQuery = controller::setQuery,
        onSetHidden = controller::setHidden,
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
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
) {
    val undo = rememberEditorUndo()
    ParcPage(modifier.background(color(skin.colors.bg)).padding(top = topPaddingDp.dp), safeTop = false, scroll = false) {
        EditorScaffold(EditorSpec.HiddenApps, skin, scroll = false, empty = state.rows.isEmpty(),
            undo = if (undo.action != null) ({ undo.run() }) else null) {
        BasicText(
            if (state.hiddenCount == 1) "1 app hidden" else "${state.hiddenCount} apps hidden",
            style = style(skin, skin.colors.textSecondary, skin.typography.caption),
        )
        QueryField(state.query, skin, onQuery)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
            items(state.rows, key = { it.appId.value }) { row ->
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
private fun QueryField(query: String, skin: Skin, onQuery: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style(skin, skin.colors.textPrimary, skin.typography.menu),
        cursorBrush = SolidColor(color(skin.colors.accent)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
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
        SettingRow(row.label, skin, SettingKind.Toggle, description = "Hide this app", checked = row.hidden, divider = false) {
            onSetHidden(row.appId, !row.hidden)
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
