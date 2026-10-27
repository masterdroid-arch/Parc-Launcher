package com.brightblu.parc

import androidx.compose.runtime.getValue
import com.brightblu.parc.feature.settings.ui.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.brightblu.parc.core.data.icons.IconPack
import com.brightblu.parc.core.data.icons.IconPackRepository
import com.brightblu.parc.model.ClockStyle
import com.brightblu.parc.model.IconShape
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WallpaperStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * T71 Look page. Every row writes the app-local [ParcLookStore] through [onChange]; the icon pack
 * list and its selection come from T70's repository, which owns that one setting. Colours and sizes
 * come only from [Skin]/[Metrics]; the two controls are real drag sliders with a pure step mapping.
 */
@Composable
internal fun LookSettingsPage(
    skin: Skin,
    look: LookSettings,
    iconPacks: IconPackRepository?,
    onChange: ((LookSettings) -> LookSettings) -> Unit,
    onBack: () -> Unit,
    clockVisible: Boolean = true,
    onSetClockVisible: (Boolean) -> Unit = {},
    services: LauncherServices? = null,
) {
    val scope = rememberCoroutineScope()
    val emptyPacks = remember { MutableStateFlow<List<IconPack>>(emptyList()) }
    val noSelection = remember { MutableStateFlow<String?>(null) }
    val packs by (iconPacks?.packs ?: emptyPacks).collectAsState()
    val selection by (iconPacks?.selection ?: noSelection).collectAsState()
    LaunchedEffect(iconPacks) { iconPacks?.refresh() }

    // OPUS1: the live home on top shows icon shape and size; the clock sample shows the clock.
    PreviewSettingsPage(skin, "Icons and clock", onBack, preview = {
        if (services != null) HomePreview(services, skin, look) else ClockPreview(skin, look, clockVisible)
    }) {
        SettingsGroup("Icons", skin) {
            SettingsOptionChips("Icon shape", skin, IconShape.entries.map(LookRules::iconShapeLabel),
                IconShape.entries.indexOf(look.iconShape), "The outline every app icon is cut to",
                sample = { index, on -> IconSample(skin, IconShape.entries[index], on = on) }) { index ->
                onChange { it.copy(iconShape = IconShape.entries[index]) }
            }
            SettingsVisualChoice("Icon size", LookRules.IconSizeSteps.indexOf(look.iconSizeDp).coerceAtLeast(0),
                LookRules.IconSizeSteps.map { "$it dp" }, skin, "Larger icons are easier to hit; arcs keep their spacing") { index ->
                onChange { it.copy(iconSizeDp = LookRules.IconSizeSteps[index]) }
            }
            SettingsChoice("Icon pack", packs.firstOrNull { it.packageName == selection }?.label ?: "None",
                listOf("None") + packs.map { it.label }, skin, "Use icons from an installed icon pack") { index ->
                scope.launch { iconPacks?.select(if (index == 0) null else packs[index - 1].packageName) }
            }
        }
        SettingsGroup("Clock", skin) {
            SettingRow("Show clock", skin, SettingKind.Toggle, "Show the time on the Clock hub page", checked = clockVisible) { onSetClockVisible(!clockVisible) }
            if (clockVisible) {
                Box(Modifier.padding(horizontal = Metrics.gridDp.dp * 4)) { ClockPreview(skin, look, clockVisible) }
                SettingsOptionChips("Clock style", skin, ClockStyle.entries.map(LookRules::clockStyleLabel),
                    ClockStyle.entries.indexOf(look.clockStyle), "How the time is drawn") { index ->
                    onChange { it.copy(clockStyle = ClockStyle.entries[index]) }
                }
                SettingRow("Date line", skin, SettingKind.Toggle, "Today’s date under the time", checked = look.showDate) { onChange { it.copy(showDate = !it.showDate) } }
                SettingRow("Next alarm", skin, SettingKind.Toggle, "Your next alarm under the time", checked = look.showAlarm, divider = false) { onChange { it.copy(showAlarm = !it.showAlarm) } }
            }
        }
    }
}
