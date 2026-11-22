package com.brightblu.parc

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
 *
 * T324: the same pieces also draw the Home sheet ([WallpaperSheet]), so the two paths can never drift apart.
 */
@Composable
internal fun WallpaperChooser(def: SettingDef, s: SettingsSnapshot, skin: Skin, look: LookSettings,
    onChange: ((LookSettings) -> LookSettings) -> Unit, chooseRequest: Int, onChoose: () -> Unit,
    onValue: (SettingDef, Int) -> Unit) {
    val picture = rememberWallpaperThumbnail(look.wallpaperImageRevision)
    val phoneColour = rememberPhoneWallpaperColour()
    // T324: the same compact row of three choice cards the Home sheet uses. It keeps the whole card
    // and its footer on one screen, so the note is never cut by the bottom edge, and both paths say
    // the same words in the same shape.
    Column(Modifier.fillMaxWidth().parcRowPadding()) {
        BasicText(def.title, style = settingsText(skin))
        if (def.help.isNotBlank()) BasicText(def.help, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
        WallpaperChoiceCards(skin, look.wallpaper, picture, phoneColour,
            modifier = Modifier.padding(top = Metrics.rowGapDp.dp)) { index ->
            if (WallpaperChoices[index] == WallpaperStyle.Image) onChoose() else onValue(def, index)
        }
    }
    SettingsDivider(skin)
    WallpaperImageControls(skin, look, onChange, chooseRequest = chooseRequest, showOptions = look.wallpaper == WallpaperStyle.Image)
}

/**
 * T324: the small picture the "A picture I choose" sample shows, decoded off the main thread. Keyed on
 * the saved picture's revision, so a new picture appears without reopening the page or the sheet.
 */
@Composable
internal fun rememberWallpaperThumbnail(revision: Long): ImageBitmap? {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
            BitmapFactory.decodeFile(WallpaperImageStore(context).file.path, opts)?.asImageBitmap()
        }
    }
    return picture
}

/** T324: the phone wallpaper's main colour comes from SettingsPreviews' one cached helper. */

/**
 * T324: one background sample, from the skin tokens only. System shows the phone wallpaper's colour,
 * Image the saved picture (or a "+" until one is chosen), anything else the skin's surface.
 */
@Composable
internal fun WallpaperSample(style: WallpaperStyle, skin: Skin, picture: ImageBitmap?, phoneColour: Int?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(skin.colors.bg.toInt())), contentAlignment = Alignment.Center) {
        when (style) {
            WallpaperStyle.System -> Box(Modifier.fillMaxSize().background(Color(phoneColour ?: skin.colors.bg.toInt())))
            WallpaperStyle.Image -> picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: BasicText("+", style = settingsText(skin, skin.colors.accent))
            else -> Box(Modifier.fillMaxSize().background(Color(skin.colors.surface.toInt())))
        }
    }
}
