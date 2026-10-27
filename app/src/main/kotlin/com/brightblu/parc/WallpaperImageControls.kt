package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun WallpaperImageControls(skin: Skin, look: LookSettings, onChange: ((LookSettings) -> LookSettings) -> Unit, chooseRequest: Int = 0, showOptions: Boolean = true) {
    val context = LocalContext.current
    val store = remember(context) { WallpaperImageStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var cropping by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { store.stage(uri) }
            busy = false
            if (ok) cropping = true else message = "Could not open this image. Choose another picture."
        }
    }
    LaunchedEffect(chooseRequest) {
        if (chooseRequest > 0 && !busy) runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { message = "The image picker is unavailable." }
    }
    if (showOptions) {
    // OPUS1: picture actions sit in one group; the dim slider is the shared visual choice.
    SettingsGroup("Picture", skin) {
    SettingRow("Crop", skin, SettingKind.Action, "Adjust the saved picture’s framing", icon = "⤢") {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { store.stage(android.net.Uri.fromFile(store.file)) }
            busy = false
            if (ok) cropping = true else message = "Choose an image first."
        }
    }
    SettingRow(if (busy) "Opening image…" else "Choose another picture", skin, SettingKind.Action, "Opens your phone’s photo picker", icon = "+") {
        if (!busy) runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { message = "The image picker is unavailable." }
    }
    SettingsVisualChoice("Dim", LookRules.DimSteps.indexOf(look.wallpaperDimPercent).coerceAtLeast(0),
        LookRules.DimSteps.map { "$it%" }, skin, "Darkens the picture so icons stand out") { index ->
        onChange { it.copy(wallpaperDimPercent = LookRules.DimSteps[index]) }
    }
    SettingRow("Remove picture", skin, SettingKind.Action, "Go back to the skin’s colour", icon = "×", divider = false) {
        scope.launch {
            withContext(Dispatchers.IO) { store.remove() }
            onChange { it.copy(wallpaper = WallpaperStyle.Black, wallpaperImageRevision = System.currentTimeMillis()) }
        }
    }
    }
    }
    if (message.isNotEmpty()) BasicText(message, style = settingsText(skin))
    if (cropping) androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) { cropping = false; store.cancel() } },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap by produceState<android.graphics.Bitmap?>(null) { value = withContext(Dispatchers.IO) { store.preview() } }
        val display = context.resources.displayMetrics
        val bounds = (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
        val aspect = bounds.width().toFloat() / bounds.height()
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        var x by rememberSaveable { mutableFloatStateOf(0f) }
        var y by rememberSaveable { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
        SettingsPageFrame(skin, onBack = { if (!busy) { cropping = false; store.cancel() } }) {
            Column(Modifier.fillMaxSize().padding(Metrics.panelGutterDp.dp)) {
                BasicText("Crop for Home", style = settingsText(skin))
                BasicText("Pinch to zoom · Drag to move\nShaded edges mark the Home safe areas", style = settingsText(skin, caption = true))
                bitmap?.let { image ->
                    val preview = remember(image) { image.asImageBitmap() }
                    val crop = WallpaperImageRules.crop(image.width, image.height, aspect, zoom, x, y)
                    Canvas(Modifier.weight(1f).align(Alignment.CenterHorizontally).aspectRatio(aspect).pointerInput(image, aspect) {
                        detectTransformGestures { _, pan, scale, _ ->
                            if (!busy) {
                                zoom = (zoom * scale).coerceIn(1f, Metrics.wallpaperMaxZoom)
                                val current = WallpaperImageRules.crop(image.width, image.height, aspect, zoom, x, y)
                                val sx = size.width / current.width
                                val sy = size.height / current.height
                                if (image.width > current.width) x = (x - 2 * pan.x / sx / (image.width - current.width)).coerceIn(-1f, 1f)
                                if (image.height > current.height) y = (y - 2 * pan.y / sy / (image.height - current.height)).coerceIn(-1f, 1f)
                            }
                        }
                    }.drawWithCache {
                        val src = IntOffset(crop.left.roundToInt(), crop.top.roundToInt())
                        val srcSize = IntSize(crop.width.toInt().coerceAtLeast(1), crop.height.toInt().coerceAtLeast(1))
                        val dst = IntSize(size.width.toInt(), size.height.toInt())
                        val tint = Color(skin.colors.bg.toInt()).copy(alpha = Metrics.wallpaperCropGuideAlpha)
                        val top = size.height * Metrics.keepOutTopDp / (bounds.height() / display.density)
                        val bottom = size.height * Metrics.keepOutBottomDp / (bounds.height() / display.density)
                        val right = size.width * Metrics.keepOutRightDp / (bounds.width() / display.density)
                        val leftSize = Size(size.width * Metrics.keepOutLeftDp / (bounds.width() / display.density), size.height)
                        val topSize = Size(size.width, top)
                        val bottomOrigin = Offset(0f, size.height - bottom)
                        val bottomSize = Size(size.width, bottom)
                        val rightOrigin = Offset(size.width - right, 0f)
                        val rightSize = Size(right, size.height)
                        val accent = Color(skin.colors.accent.toInt())
                        val stroke = Stroke(skin.lineWidths.baseDp)
                        onDrawBehind {
                            drawImage(preview, src, srcSize, dstSize = dst)
                            drawRect(tint, size = leftSize)
                            drawRect(tint, size = topSize)
                            drawRect(tint, bottomOrigin, bottomSize)
                            drawRect(tint, rightOrigin, rightSize)
                            drawRect(accent, style = stroke)
                        }
                    }) {}
                    SettingRow(if (busy) "Saving…" else "Use image", skin, SettingKind.Action) {
                        if (!busy) scope.launch {
                            busy = true
                            val bright = withContext(Dispatchers.IO) { wallpaperBrightness(image, aspect, zoom, x, y) > Metrics.wallpaperBrightThreshold }
                            val ok = withContext(Dispatchers.IO) { store.save(image, aspect, zoom, x, y) }
                            busy = false
                            if (ok) {
                                onChange { it.copy(wallpaper = WallpaperStyle.Image, wallpaperImageRevision = System.currentTimeMillis()) }
                                message = if (bright && WallpaperReadability.luminance(skin.colors.bg.toInt()) < .5) "Bright image: keep Keep text readable on for clear text." else "Image saved"
                                cropping = false
                            } else message = "Could not save this image (maximum 2 MB)."
                        }
                    }
                } ?: BasicText("Image unavailable. Go back and choose another picture.", style = settingsText(skin))
                if (message.isNotEmpty()) BasicText(message, style = settingsText(skin))
            }
        }
        }
    }
}
