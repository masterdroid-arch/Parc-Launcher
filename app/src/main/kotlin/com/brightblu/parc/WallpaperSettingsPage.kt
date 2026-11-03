package com.brightblu.parc

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Look > Wallpaper, the "What sits behind Parc" row (registry id `WallpaperImage`, T114 design kept): three visual choices,
 * then the picture's own actions (crop, choose another, remove) when a picture is chosen. Dim and Keep text readable are
 * plain registry rows right after it. Choosing "A picture I choose" opens the photo picker; the picture is saved first.
 */
@Composable
internal fun WallpaperChooser(def: SettingDef, s: SettingsSnapshot, skin: Skin, look: LookSettings,
    onChange: ((LookSettings) -> LookSettings) -> Unit, chooseRequest: Int, onChoose: () -> Unit,
    onValue: (SettingDef, Int) -> Unit) {
    val context = LocalContext.current
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, look.wallpaperImageRevision) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
            BitmapFactory.decodeFile(WallpaperImageStore(context).file.path, opts)?.asImageBitmap()
        }
    }
    val phoneColour = remember {
        runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
    }
    val selected = def.read?.invoke(s) ?: 0
    SettingsOptionChips(def.title, skin, def.options(s), selected, def.help,
        sample = { index, _ ->
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(Metrics.gridDp.dp * 2)
            Box(Modifier.fillMaxSize().clip(shape).background(Color(when (WallpaperChoices[index]) {
                WallpaperStyle.System -> phoneColour ?: skin.colors.bg.toInt()
                WallpaperStyle.Image -> skin.colors.bg.toInt()
                else -> skin.colors.surface.toInt()
            })), contentAlignment = Alignment.Center) {
                if (WallpaperChoices[index] == WallpaperStyle.Image) {
                    picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: BasicText("+", style = settingsText(skin, skin.colors.accent))
                }
            }
        }) { index ->
        if (WallpaperChoices[index] == WallpaperStyle.Image) onChoose() else onValue(def, index)
    }
    WallpaperImageControls(skin, look, onChange, chooseRequest = chooseRequest, showOptions = look.wallpaper == WallpaperStyle.Image)
}
