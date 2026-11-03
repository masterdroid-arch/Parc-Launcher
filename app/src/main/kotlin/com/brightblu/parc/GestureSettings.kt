package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.ParcPage
import com.brightblu.parc.model.parcBottomInset
import com.brightblu.parc.model.parcBottomInsetPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.brightblu.parc.feature.settings.ui.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** Which list the gesture settings page is showing. Pages/Apps are the two pickers. */
private enum class GestureStage { Actions, Pages, Apps }

/**
 * T69: one gesture's action list. `Swipe up` and `Hub long-press` share this page; the hub adds
 * `Edit mode` because it is that gesture's default. Every label, size and colour comes from
 * [Skin]/[Metrics]; the pure rows come from [gestureRows].
 */
@Composable
internal fun GestureSettingsPage(
    skin: Skin,
    slot: GestureSlot,
    current: GestureAction,
    pages: List<String>,
    apps: List<AppEntry>,
    onPick: (GestureAction) -> Unit,
    onBack: () -> Unit,
) {
    var stage by remember(slot) { mutableStateOf(GestureStage.Actions) }
    BackHandler(enabled = stage != GestureStage.Actions) { stage = GestureStage.Actions }
    val appLabel: (AppId) -> String? = { id -> apps.firstOrNull { it.id == id }?.label }
    SettingsPageFrame(skin, onBack = {
        if (stage == GestureStage.Actions) onBack() else stage = GestureStage.Actions
    }) {
        ParcPage(safeTop = false, scroll = false, padBottom = false) {
            BasicText(
                if (slot == GestureSlot.SwipeUp) "Swipe up" else "Hub long-press",
                style = gestureStyle(skin, skin.colors.textPrimary, skin.typography.title),
            )
            BasicText(
                when (stage) {
                    GestureStage.Actions -> if (slot == GestureSlot.SwipeUp) "Choose what a swipe up does"
                        else "Default is edit mode"
                    GestureStage.Pages -> "Stats hub page"
                    GestureStage.Apps -> "Open an app"
                },
                style = gestureStyle(skin, skin.colors.textSecondary, skin.typography.caption),
                modifier = Modifier.padding(top = Metrics.settingsSubtitleGapDp.dp),
            )
            when (stage) {
                GestureStage.Actions -> Column(Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp).verticalScroll(rememberScrollState()).parcBottomInset(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    gestureRows(slot, current, appLabel).forEach { row ->
                        when (row) {
                            is GestureRow.Pick -> GesturePickRow(skin, row.label, null, row.checked) {
                                onPick(row.action)
                            }
                            is GestureRow.Picker -> GesturePickRow(skin, row.label, row.detail, row.checked) {
                                stage = if (row.picker == GesturePicker.HubPage) GestureStage.Pages
                                    else GestureStage.Apps
                            }
                        }
                    }
                }
                GestureStage.Pages -> Column(Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp).verticalScroll(rememberScrollState()).parcBottomInset(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    gesturePageRows(pages, current).forEach { row ->
                        GesturePickRow(skin, row.label, null, row.checked) {
                            onPick(row.action)
                            stage = GestureStage.Actions
                        }
                    }
                    if (pages.isEmpty()) {
                        BasicText(
                            "Every stats page is hidden",
                            style = gestureStyle(skin, skin.colors.textSecondary, skin.typography.caption),
                            modifier = Modifier.padding(Metrics.sectionHeaderGapDp.dp),
                        )
                    }
                }
                GestureStage.Apps -> LazyColumn(Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp),
                    contentPadding = parcBottomInsetPadding(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    items(gestureAppRows(apps, current), key = { it.action.encode() }) { row ->
                        GesturePickRow(skin, row.label, null, row.checked) {
                            onPick(row.action)
                            stage = GestureStage.Actions
                        }
                    }
                }
            }
        }
    }
}

/** One selectable row. A checked row uses the accent; a picker shows its current choice instead. */
@Composable
private fun GesturePickRow(
    skin: Skin,
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    SettingsCard(skin) {
        SettingRow(label, skin, if (detail != null) SettingKind.Choice else SettingKind.Action,
            value = detail.orEmpty(), icon = if (checked) "✓" else "○", divider = false, onClick = onClick)
    }
}

private fun gestureStyle(skin: Skin, tint: Long, token: com.brightblu.parc.model.TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(tint.toInt()),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
