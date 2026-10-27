package com.brightblu.parc

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
        Column(Modifier.fillMaxSize().padding(horizontal = Metrics.textGutterDp.dp)) {
            BasicText(title, style = settingsTitle(skin))
            preview()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(bottom = Metrics.keepOutBottomDp.dp), content = content)
        }
    }
}

internal fun settingsTitle(skin: Skin): TextStyle = TextStyle(
    fontFamily = if (skin.typography.fallbackFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else null,
    color = Color(skin.colors.textPrimary.toInt()), fontSize = skin.typography.title.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.title.weight), letterSpacing = skin.typography.title.trackingSp.sp)

/**
 * The real home in miniature with [look], [counts] and [aligned] applied (null = the saved value).
 * Touches are swallowed so nothing in the preview launches or changes.
 */
@Composable
internal fun HomePreview(services: LauncherServices, skin: Skin, look: LookSettings,
    counts: LayoutCounts? = null, aligned: Boolean? = null, caption: String = "Preview of your home") {
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
    LaunchedEffect(skin, scene, look, phoneColour) {
        delay(Metrics.settingsPreviewDebounceMs)
        ring = withContext(Dispatchers.Default) {
            // The phone wallpaper itself is not readable without a photo permission, so the system
            // choice is shown as its reported main colour (dimmed like Home), as the Wallpaper page does.
            val fallback = if (look.wallpaper == WallpaperStyle.System && phoneColour != null) {
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    eraseColor(SettingsSampleRules.dimmed(phoneColour, look.wallpaperDimPercent))
                }
            } else null
            rasterSkinHome(scene, services, skin, look, wallpaperFile, fallback)
        }
    }
    SettingsPreviewFrame(skin, caption) {
        val current = ring
        if (current == null) BasicText("Preparing preview…", style = settingsText(skin, skin.colors.textSecondary, true))
        else CompositionLocalProvider(LocalHubLook provides look) { SkinHome(skin, services, scene, current, look) }
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}

/** The clock exactly as the Clock hub page draws it, with the date and alarm lines chosen. */
@Composable
internal fun ClockPreview(skin: Skin, look: LookSettings, clockVisible: Boolean) {
    val context = LocalContext.current
    val time = rememberHubTime()
    val detail = remember(look.showDate, look.showAlarm, time) {
        val zone = java.time.ZoneId.systemDefault()
        LookClock.detail(look.showDate, look.showAlarm, java.time.LocalDate.now(zone),
            nextAlarmMillis(context), System.currentTimeMillis(), zone)
    }
    SettingsPreviewFrame(skin, "Clock preview", Metrics.settingsClockPreviewHeightDp) {
        if (!clockVisible || look.clockStyle == ClockStyle.Hidden) {
            BasicText("The clock is hidden", style = settingsText(skin, skin.colors.textSecondary))
        } else ClockHubPage(skin, look, time, detail, true)
    }
}

@Composable
private fun rememberPhoneWallpaperColour(): Int? {
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

/** Compose shape that matches the ring's icon mask for a sample chip. */
internal fun iconShapeSample(shape: IconShape): Shape = when (shape) {
    IconShape.Circle -> CircleShape
    IconShape.Squircle -> RoundedCornerShape(percent = 35)
    IconShape.RoundedSquare -> RoundedCornerShape(percent = 22)
    IconShape.None -> RectangleShape
}

/** A filled icon placeholder in [shape]; [fraction] of the sample box, for size choices. */
@Composable
internal fun IconSample(skin: Skin, shape: IconShape, fraction: Float = 0.8f, on: Boolean = true) {
    Box(Modifier.fillMaxSize(fraction).clip(iconShapeSample(shape))
        .background(Color((if (on) skin.colors.accent else skin.colors.lineActive).toInt())))
}

/** Three colour dots of a skin on its own background: a quick visual of the chosen skin. */
@Composable
internal fun SkinSwatch(skin: Skin) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(skin.shape.surfaceCornerDp.coerceAtMost(Metrics.gridDp * 3).dp))
        .background(Color(skin.colors.bg.toInt())).border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineActive.toInt()),
            RoundedCornerShape(skin.shape.surfaceCornerDp.coerceAtMost(Metrics.gridDp * 3).dp)),
        contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp / 2)) {
            listOf(skin.colors.accent, skin.colors.textPrimary, skin.colors.lineActive).forEach { tint ->
                Box(Modifier.size(Metrics.gridDp.dp * 2).clip(CircleShape).background(Color(tint.toInt())))
            }
        }
    }
}

/** What sits behind Parc, in a thumbnail: phone colour, the saved picture, or the skin colour. */
@Composable
internal fun WallpaperSwatch(skin: Skin, look: LookSettings) {
    val context = LocalContext.current
    val phone = rememberPhoneWallpaperColour()
    val picture by produceState<ImageBitmap?>(null, look.wallpaper, look.wallpaperImageRevision) {
        value = if (look.wallpaper != WallpaperStyle.Image) null else withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(WallpaperImageStore(context).file.path,
                    BitmapFactory.Options().apply { inSampleSize = 16 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(Metrics.gridDp.dp * 2)
    val base = when (look.wallpaper) {
        WallpaperStyle.System -> phone ?: skin.colors.bg.toInt()
        WallpaperStyle.Solid -> skin.colors.surface.toInt()
        else -> skin.colors.bg.toInt()
    }
    Box(Modifier.fillMaxSize().clip(shape).background(Color(base))
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineActive.toInt()), shape)) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = look.wallpaperDimPercent / 100f)))
        }
    }
}

/** The hub panel at its opacity over a striped accent background, so see-through is visible. */
@Composable
internal fun PanelSample(skin: Skin, opacityPercent: Int) {
    val accent = Color(skin.colors.accent.toInt())
    val panel = Color(skin.colors.bg.toInt()).copy(alpha = opacityPercent / 100f)
    val line = Color(skin.colors.lineActive.toInt())
    Box(Modifier.fillMaxSize().drawWithCache {
        val stroke = Stroke(1.dp.toPx())
        val stripe = size.width / 5f
        val inset = size.width * 0.18f
        val inner = Size(size.width - inset * 2, size.height - inset * 2)
        val stripeSize = Size(stripe, size.height)
        onDrawBehind {
            for (i in 0 until 5) if (i % 2 == 0) drawRect(accent, Offset(i * stripe, 0f), stripeSize)
            drawRect(panel, Offset(inset, inset), inner)
            drawRect(line, Offset(inset, inset), inner, style = stroke)
        }
    })
}

/** Concentric quarter arcs from the bottom-right corner with a dot per visible icon. */
@Composable
internal fun ArcsSample(skin: Skin, arcs: Int, iconsPerArc: Int) {
    val lineTint = Color(skin.colors.lineActive.toInt())
    val dotTint = Color(skin.colors.accent.toInt())
    val dots = if (iconsPerArc == 0) 4 else iconsPerArc.coerceIn(2, 7)
    // Geometry is computed once per size in the cache block; drawing only reads the arrays.
    Box(Modifier.fillMaxSize().drawWithCache {
        val stroke = Stroke(1.dp.toPx())
        val dot = size.minDimension / 22f
        val radii = FloatArray(arcs) { a -> size.minDimension * (0.32f + a * 0.6f / arcs) }
        val xs = FloatArray(arcs * dots)
        val ys = FloatArray(arcs * dots)
        for (a in 0 until arcs) for (i in 0 until dots) {
            val angle = Math.toRadians(180.0 + 90.0 * (i + 0.5) / dots)
            xs[a * dots + i] = size.width + (radii[a] * kotlin.math.cos(angle)).toFloat()
            ys[a * dots + i] = size.height + (radii[a] * kotlin.math.sin(angle)).toFloat()
        }
        onDrawBehind {
            for (a in 0 until arcs) {
                val r = radii[a]
                drawArc(lineTint, 180f, 90f, false, Offset(size.width - r, size.height - r), Size(r * 2, r * 2), style = stroke)
            }
            for (k in xs.indices) drawCircle(dotTint, dot, Offset(xs[k], ys[k]))
        }
    })
}
