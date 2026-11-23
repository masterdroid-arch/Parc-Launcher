package com.brightblu.parc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsGroupGlyph
import com.brightblu.parc.feature.settings.ui.SettingsIcon
import com.brightblu.parc.feature.settings.ui.SettingsLayoutRules
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WallpaperStyle

/**
 * T373: the six everyday settings the Settings root shows as value tiles. A tile is not a new setting: it points at
 * one registry row ([rowId]), reads the value from the same [SettingsSnapshot] the group page reads, and either
 * flips that row in place ([inPlace], the three switches) or opens the page that holds it. Every id, value and
 * write stays the registry's, so search, deep links and backups see nothing new.
 */
internal enum class SettingsShortcut(val rowId: String, val label: String, val icon: SettingsIcon, val group: SettingsGroup) {
    Skin("Appearance", "Skin", SettingsIcon.Appearance, SettingsGroup.Appearance),
    Wallpaper("WallpaperImage", "Wallpaper", SettingsIcon.Wallpaper, SettingsGroup.Appearance),
    Arcs("ArcsShown", "Arcs", SettingsIcon.Home, SettingsGroup.Home),
    Glass("GlassEffect", "Glass", SettingsIcon.Glass, SettingsGroup.Appearance),
    LeftHand("LeftHand", "Left hand", SettingsIcon.LeftHand, SettingsGroup.Home),
    LockLayout("LockLayout", "Lock layout", SettingsIcon.LockLayout, SettingsGroup.Home);

    val def: SettingDef get() = SettingsRegistry.byId.getValue(rowId)

    /** The three switches change in place; the other three open the page that holds the row. */
    val inPlace: Boolean get() = def.control == SettingControl.Switch

    /** The same words on the tile and on the group page. */
    fun value(s: SettingsSnapshot): String = when (this) {
        Skin -> s.skin.name
        Wallpaper -> wallpaperSourceLabel(s.look.wallpaper)
        Arcs -> arcsLabel(s.counts.arcs)
        else -> def.value(s)
    }

    /** The registry value that a tap writes; null when the shortcut opens a page instead. */
    fun toggledIndex(s: SettingsSnapshot): Int? = if (inPlace) 1 - (def.read?.invoke(s) ?: 0) else null

    companion object {
        /** The tiles in root order: two rows of three. */
        val root: List<SettingsShortcut> = listOf(Skin, Wallpaper, Arcs, Glass, LeftHand, LockLayout)

        fun of(group: SettingsGroup): List<SettingsShortcut> = root.filter { it.group == group }
    }
}

internal fun arcsLabel(arcs: Int): String = if (arcs == 1) "1 ring" else "$arcs rings"

private val WallpaperSourceLabels = listOf("Phone", "Picture", "Skin colour")

/** The source the wallpaper row stores, in the words the Wallpaper sheet uses. */
internal fun wallpaperSourceLabel(style: WallpaperStyle): String = WallpaperSourceLabels[wallpaperChoiceIndex(style)]

/** Rows of [columns] tiles; the last row keeps its tiles the same width as the others. */
internal fun <T> tileRows(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))

/**
 * The glass tiles. [glassSkin] is the stats hub's resolved skin, so the tiles carry the same tint and glass
 * preference as Home; with glass off they are flat cards. [focus] gives Back a tile to return to.
 */
@Composable
internal fun SettingsShortcutTiles(
    snapshot: SettingsSnapshot, skin: Skin, glassSkin: Skin, focus: (SettingsShortcut) -> FocusRequester,
    onTap: (SettingsShortcut) -> Unit,
) {
    val columns = SettingsLayoutRules.tileColumns(LocalDensity.current.fontScale)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.settingsTileGapDp.dp)) {
        tileRows(SettingsShortcut.root, columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Metrics.settingsTileGapDp.dp)) {
                row.forEach { item ->
                    ShortcutTile(item, snapshot, skin, glassSkin, focus(item), Modifier.weight(1f).fillMaxHeight()) { onTap(item) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ShortcutTile(item: SettingsShortcut, s: SettingsSnapshot, skin: Skin, glassSkin: Skin,
    focus: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    val value = item.value(s)
    val gesture = if (item.inPlace) Modifier.toggleable(value == "On", role = Role.Switch) { onClick() }
        else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(modifier.heightIn(min = Metrics.settingsTileMinHeightDp.dp)
        .settingsSurface(glassSkin, cornerDp = Metrics.settingsTileCornerDp)
        .focusRequester(focus).then(gesture).padding(Metrics.settingsTilePaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
        SettingsGroupGlyph(item.icon, skin)
        Spacer(Modifier.size(Metrics.itemGapDp.dp))
        BasicText(item.label, style = settingsText(skin).copy(fontWeight = FontWeight.Medium))
        BasicText(value, style = settingsText(skin, skin.colors.textSecondary))
    }
}

/**
 * The group page's card of the same shortcuts: a switch where the tile switches in place, otherwise a row that opens
 * the page, each reading the value its tile reads.
 */
@Composable
internal fun SettingsShortcutRows(group: SettingsGroup, snapshot: SettingsSnapshot, skin: Skin,
    onTap: (SettingsShortcut) -> Unit) {
    val items = SettingsShortcut.of(group)
    if (items.isEmpty()) return
    SettingsCard(skin) {
        items.forEachIndexed { index, item ->
            val divider = index < items.lastIndex
            if (item.inPlace) SettingRow(item.label, skin, SettingKind.Toggle, checked = item.value(snapshot) == "On",
                leading = { SettingsGroupGlyph(item.icon, skin) }, divider = divider) { onTap(item) }
            else SettingRow(item.label, skin, SettingKind.Page, value = item.value(snapshot),
                leading = { SettingsGroupGlyph(item.icon, skin) }, divider = divider) { onTap(item) }
        }
    }
}

/** T375: the registry rows the Hub pages page draws above the page switches. */
internal object HubPagesLayout {
    val leadIds: Set<String> = setOf("StatsVisible", "HubSize", "HubFullScreen")
}
