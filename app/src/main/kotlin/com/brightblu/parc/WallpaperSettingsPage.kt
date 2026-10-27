package com.brightblu.parc

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun wallpaperChoiceLabel(style: WallpaperStyle): String = when (style) {
    WallpaperStyle.System -> "My phone’s wallpaper"
    WallpaperStyle.Image -> "A picture I choose"
    else -> "Just the skin’s colour"
}

/**
 * Look / Wallpaper (T114, OPUS1 design pass): a live home preview on top, then one question with
 * three visual choices, then only the options of the chosen one. Stored keys are unchanged.
 */
@Composable
internal fun WallpaperSettingsPage(skin: Skin, look: LookSettings, services: LauncherServices,
    onChange: ((LookSettings) -> LookSettings) -> Unit, target: SettingsRowId? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var choose by remember { mutableIntStateOf(0) }
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, look.wallpaperImageRevision) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
            BitmapFactory.decodeFile(WallpaperImageStore(context).file.path, opts)?.asImageBitmap()
        }
    }
    val phoneColour = remember {
        runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
    }
    val options = listOf(WallpaperStyle.System, WallpaperStyle.Image, WallpaperStyle.Solid)
    val selected = when (look.wallpaper) {
        WallpaperStyle.System -> 0
        WallpaperStyle.Image -> 1
        else -> 2
    }
    PreviewSettingsPage(skin, "Wallpaper", onBack, preview = { HomePreview(services, skin, look) }) {
        SettingsGroup("Background", skin, footer = "The phone wallpaper is shown as its main colour here; Home shows the real picture.") {
            SettingsOptionChips("What sits behind Parc?", skin, options.map(::wallpaperChoiceLabel), selected,
                divider = look.wallpaper == WallpaperStyle.System,
                sample = { index, _ ->
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Metrics.gridDp.dp * 2)
                    Box(Modifier.fillMaxSize().clip(shape).background(Color(when (options[index]) {
                        WallpaperStyle.System -> phoneColour ?: skin.colors.bg.toInt()
                        WallpaperStyle.Image -> skin.colors.bg.toInt()
                        else -> skin.colors.surface.toInt()
                    })), contentAlignment = Alignment.Center) {
                        if (options[index] == WallpaperStyle.Image) {
                            picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                ?: BasicText("+", style = settingsText(skin, skin.colors.accent))
                        }
                    }
                }) { index ->
                if (options[index] == WallpaperStyle.Image) choose++
                else onChange { it.copy(wallpaper = options[index]) }
            }
            if (look.wallpaper == WallpaperStyle.System) {
                SettingsAnchor(SettingsRowId.WallpaperDim, target) {
                    SettingsVisualChoice("Dim", LookRules.DimSteps.indexOf(look.wallpaperDimPercent).coerceAtLeast(0),
                        LookRules.DimSteps.map { "$it%" }, skin, "Darkens the wallpaper so icons stand out", divider = false) { index ->
                        onChange { it.copy(wallpaperDimPercent = LookRules.DimSteps[index]) }
                    }
                }
            }
        }
        if (look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image) {
            SettingsGroup("Readability", skin) {
                SettingsAnchor(SettingsRowId.WallpaperReadability, target) {
                    SettingRow("Keep text readable", skin, SettingKind.Toggle, "Shades the picture behind text and the hub",
                        checked = look.wallpaperReadability, divider = false) {
                        onChange { it.copy(keepTextReadable = !it.wallpaperReadability, wallpaperReadability = !it.wallpaperReadability, imageReadabilityScrim = !it.wallpaperReadability) }
                    }
                }
            }
        }
        SettingsAnchor(if (look.wallpaper == WallpaperStyle.Image) SettingsRowId.WallpaperDim else SettingsRowId.WallpaperImage, target) {
            WallpaperImageControls(skin, look, onChange, chooseRequest = choose, showOptions = look.wallpaper == WallpaperStyle.Image)
        }
    }
}
