package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.ui.SettingsTheme
import com.brightblu.parc.feature.settings.ui.SettingsVisualChoice
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WallpaperStyle
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.ring.module.frostedBackground

/**
 * T324: Home menu > "Wallpaper and look" opens this sheet over Home instead of switching to the
 * Settings page. It carries the same three choices, the Dim slider and the picture's own actions;
 * Settings > Look > Wallpaper still opens the full page with exactly the same pieces.
 *
 * A tap on the scrim or Back closes it, like every other chooser sheet. The body never grows past
 * [Metrics.wallpaperSheetMaxHeightFraction] of the screen: past that it scrolls, and its note is
 * always the last thing in the scroller so a sentence is never cut by the gesture bar.
 */
@Composable
internal fun WallpaperSheet(
    skin: Skin,
    look: LookSettings,
    onChange: ((LookSettings) -> LookSettings) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val picture = rememberWallpaperThumbnail(look.wallpaperImageRevision)
    val phoneColour = rememberPhoneWallpaperColour()
    var chooseRequest by remember { mutableIntStateOf(0) }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val bodyMaxDp = (screenHeightDp * Metrics.wallpaperSheetMaxHeightFraction).dp
    SettingsTheme(skin) {
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
                // Taps inside the sheet never reach the scrim.
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(bottom = rememberPageInsets().bottomDp.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                    .padding(start = Metrics.screenGutterDp.dp, end = Metrics.actionEdgeGapDp.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        WallpaperSheetRules.Title,
                        Modifier.weight(1f).semantics { heading() },
                        style = TextStyle(
                            fontFamily = skin.textFamily,
                            color = Color(skin.colors.textPrimary.toInt()),
                            fontSize = skin.typography.menu.sizeSp.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Box(Modifier.clickable(onClick = onClose)
                        .heightIn(min = Metrics.touchTargetDp.dp)
                        .padding(horizontal = Metrics.itemGapDp.dp),
                        contentAlignment = Alignment.Center) {
                        BasicText("Close", style = settingsText(skin, skin.colors.accent))
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = bodyMaxDp).verticalScroll(rememberScrollState())) {
                    WallpaperChoiceCards(skin, look.wallpaper, picture, phoneColour,
                        modifier = Modifier.padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.rowGapDp.dp)) { index ->
                        if (WallpaperChoices[index] == WallpaperStyle.Image) chooseRequest++
                        else onChange { it.copy(wallpaper = WallpaperChoices[index]) }
                    }
                    if (WallpaperSheetRules.showsDim(look.wallpaper)) {
                        val steps = LookRules.DimSteps
                        // The stored value is always a step; the rounding also covers a value written
                        // by a future range so the slider still opens on the nearest step.
                        val index = LookRules.stepIndex(look.wallpaperDimPercent / 100f, steps)
                        SettingsVisualChoice(WallpaperSheetRules.Dim, index, steps.map { "$it %" }, skin,
                            WallpaperSheetRules.DimHelp, divider = false) { picked ->
                            onChange { it.copy(wallpaperDimPercent = steps[picked.coerceIn(steps.indices)]) }
                        }
                    }
                    WallpaperImageControls(skin, look, onChange, chooseRequest = chooseRequest,
                        showOptions = look.wallpaper == WallpaperStyle.Image)
                    val note = WallpaperSheetRules.note(look.wallpaper)
                    if (note.isNotEmpty()) {
                        BasicText(note,
                            style = settingsText(skin, skin.colors.textSecondary, true),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.screenGutterDp.dp,
                                vertical = Metrics.sectionHeaderGapDp.dp))
                    }
                    Spacer(Modifier.height(Metrics.rowGapDp.dp))
                }
            }
        }
    }
}

/**
 * The three choices as one row of compact cards: a sample, then its name. They use the skin's
 * surface corner and border, and the selected card gets the accent border, exactly like the
 * Settings chips. T324: the Settings page and the Home sheet both draw this same row.
 */
@Composable
internal fun WallpaperChoiceCards(skin: Skin, current: WallpaperStyle, picture: ImageBitmap?, phoneColour: Int?,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val selected = wallpaperChoiceIndex(current)
    val shape = RoundedCornerShape(skin.shape.surfaceCornerDp.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        WallpaperChoices.forEachIndexed { index, style ->
            val on = index == selected
            Column(Modifier.weight(1f).heightIn(min = Metrics.touchTargetDp.dp)
                .clip(shape)
                .background(Color(skin.colors.bg.toInt()), shape)
                .border(maxOf(skin.shape.surfaceBorderDp, 1f).dp * (if (on) 2 else 1),
                    Color((if (on) skin.colors.accent else skin.colors.lineActive).toInt()), shape)
                .selectable(on, role = Role.RadioButton) { onSelect(index) }
                .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.rowGapDp.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.lineGapDp.dp)) {
                Box(Modifier.size(Metrics.settingsSampleDp.dp).clip(RoundedCornerShape(Metrics.gridDp.dp * 2))) {
                    WallpaperSample(style, skin, picture, phoneColour, Modifier.fillMaxSize())
                }
                BasicText(
                    wallpaperChoiceLabel(style),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = settingsText(skin, if (on) skin.colors.readableAccent else skin.colors.textPrimary, true)
                        .copy(textAlign = TextAlign.Center),
                )
            }
        }
    }
}

/** T324: pure parts of the sheet, kept out of Compose so the words and rules can be unit tested. */
internal object WallpaperSheetRules {
    const val Title = "Wallpaper and look"
    const val Dim = "Dim"
    const val DimHelp = "Darkens the wallpaper or picture so icons stand out. Glass cards darken with it"

    /** The three choices, in the same order and with the same words as Settings > Look > Wallpaper. */
    val choices: List<WallpaperStyle> get() = WallpaperChoices

    /** Dim only means something when a picture or the phone wallpaper sits behind Home. */
    fun showsDim(style: WallpaperStyle): Boolean = style == WallpaperStyle.System || style == WallpaperStyle.Image

    /**
     * The same sentence the Settings page shows under its Wallpaper card. Empty for a colour-only
     * background, where there is nothing to explain.
     */
    fun note(style: WallpaperStyle): String = if (style == WallpaperStyle.System)
        "Parc cannot read the phone wallpaper's picture, so glass frosts its main colour." else ""
}
