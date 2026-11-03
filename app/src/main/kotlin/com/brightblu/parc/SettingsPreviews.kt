package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * OPUS1: live previews and small samples for visual settings. The home preview is the production
 * home (same ring raster, panel and clock page the skin gallery uses), drawn from the values being
 * edited, so every change on a Look or Layout page is visible before leaving Settings. Rasters are
 * prepared off the main thread after a short pause; the previous picture stays up meanwhile.
 * Samples draw only tokens from the skin and sizes from Metrics, with nothing allocated per frame.
 */

/** A page with a fixed live preview on top and scrolling controls under it. */
@Composable
internal fun PreviewSettingsPage(skin: Skin, title: String, onBack: () -> Unit,
    preview: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SettingsPageFrame(skin, onBack) {
        ParcPage(safeTop = false, scroll = false, padBottom = false) {
            BasicText(title, style = settingsTitle(skin))
            preview()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).parcBottomInset(), content = content)
        }
    }
}

internal fun settingsTitle(skin: Skin): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = Color(skin.colors.textPrimary.toInt()), fontSize = skin.typography.title.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.title.weight), letterSpacing = skin.typography.title.trackingSp.sp)

/**
 * The real home in miniature with [look], [counts] and [aligned] applied (null = the saved value).
 * Touches are swallowed so nothing in the preview launches or changes.
 */
@Composable
internal fun HomePreview(services: LauncherServices, skin: Skin, look: LookSettings,
    counts: LayoutCounts? = null, aligned: Boolean? = null, caption: String = "Preview of your home",
    appearance: HubAppearance? = null) {
    val context = LocalContext.current
    val layout by services.layout.layout.collectAsState()
    val liveApps by services.unlockedApps.collectAsState()
    val apps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else liveApps
    val revision by services.iconRevision.collectAsState()
    val prefs = remember(context) { ParcUiPrefs(context) }
    val scene = remember(layout, apps, revision, counts, aligned) {
        skinPreviewScene(services, layout, apps, prefs, counts, aligned)
    }
    val phoneColour = rememberPhoneWallpaperColour()
    val wallpaperFile = remember(context) { WallpaperImageStore(context).file }
    var ring by remember { mutableStateOf<ImageBitmap?>(null) }
    val saved = rememberHubAppearance()
    val hubSize = rememberHubSize()
    val statusInset = rememberStatusInsetDp()
    @Suppress("NAME_SHADOWING") val appearance = appearance ?: saved
    LaunchedEffect(skin, scene, look, phoneColour, appearance, hubSize) {
        delay(Metrics.settingsPreviewDebounceMs)
        // T178: Home's own stage, cards and arcs (rasterHome); the phone wallpaper, which Parc cannot read, is its main colour.
        ring = withContext(Dispatchers.Default) { rasterSkinHome(scene, services, skin, look, wallpaperFile, phoneColour, appearance, hubSize, statusInset, context.resources) }
    }
    SettingsPreviewFrame(skin, caption) {
        val current = ring
        if (current == null) BasicText("Preparing preview…", style = settingsText(skin, skin.colors.textSecondary, true))
        else CompositionLocalProvider(LocalHubLook provides look) { SkinHome(skin, services, scene, current, look, appearance) }
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}

@Composable
internal fun rememberPhoneWallpaperColour(): Int? {
    val context = LocalContext.current
    return remember(context) {
        runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb() }.getOrNull()
    }
}

/** Pure rules behind the samples, kept free of Compose so they are unit-tested. */
internal object SettingsSampleRules {
    /** Share of the sample box an icon of [sizeDp] fills: half at the smallest size, all at the largest. */
    fun iconFraction(sizeDp: Int): Float {
        val span = (LookRules.MaxIconSizeDp - LookRules.MinIconSizeDp).toFloat()
        return 0.5f + 0.5f * ((sizeDp - LookRules.MinIconSizeDp) / span).coerceIn(0f, 1f)
    }

    /** The phone-wallpaper stand-in: the reported colour darkened by the dim percentage. */
    fun dimmed(argb: Int, dimPercent: Int): Int {
        val keep = 1f - dimPercent.coerceIn(0, 100) / 100f
        fun channel(shift: Int) = (((argb shr shift) and 0xFF) * keep).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

