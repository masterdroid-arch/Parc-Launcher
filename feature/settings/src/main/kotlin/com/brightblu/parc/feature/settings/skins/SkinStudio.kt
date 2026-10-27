package com.brightblu.parc.feature.settings.skins

import android.net.Uri
import com.brightblu.parc.feature.settings.ui.*
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.skins.ColorMath
import com.brightblu.parc.core.data.skins.Hsv
import com.brightblu.parc.core.data.skins.SkinExchange
import com.brightblu.parc.core.data.skins.SkinImportResult
import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlin.math.cos
import kotlin.math.sin

/** What the skin studio needs from the host; every call is cheap and main-thread safe. */
class SkinStudioHost(
    val builtIn: List<Skin>,
    val custom: List<Skin>,
    val activeId: String,
    val saveSkin: (Skin) -> Boolean,
    val deleteSkin: (String) -> Unit,
    val useSkin: (String) -> Unit,
)

/**
 * Skin studio (T85): custom skins list, import and export through the Storage Access Framework (no
 * storage permission), and the colour editor with a live preview. The preview is a small scene drawn
 * from the working colours, not the real renderer. Content only; the host adds the page frame.
 */
@Composable
fun SkinStudioContent(host: SkinStudioHost, skin: Skin) {
    var model by remember { mutableStateOf<SkinEditorModel?>(null) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    var exporting by remember { mutableStateOf<Skin?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(SkinExchange.MaxBytes + 1).toByteArray().decodeToString() }
        }.getOrNull()
        message = if (text == null) "Could not read that file" else when (val r = SkinExchange.import(text, host.builtIn.first(),
            (host.builtIn + host.custom).map { it.id }.toSet())) {
            is SkinImportResult.Rejected -> r.reason
            is SkinImportResult.Imported -> if (host.saveSkin(r.skin)) "Imported ${r.skin.name}" + (r.notes.firstOrNull()?.let { ". $it" } ?: "") else "Could not save the skin"
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val s = exporting; exporting = null
        if (uri == null || s == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(SkinExchange.export(s).toByteArray()) }
            "Exported ${s.name}"
        }.getOrElse { "Could not write the file" }
    }

    val undo = rememberEditorUndo()
    val editing = model
    key(editing?.base?.id ?: "skin-list") {
    EditorScaffold(EditorSpec.Skin, skin, empty = editing == null && host.custom.isEmpty(),
        instruction = if (editing == null) "Tap a skin to edit or Add a copy from the built-in list; changes save automatically." else EditorSpec.Skin.instruction,
        undo = if (undo.action != null) ({ undo.run() }) else null) {
    if (editing != null) {
        SkinEditor(editing, host, skin, onModel = { model = it }, onClose = { model = null },
            onExport = { s -> exporting = s; exporter.launch(SkinExchange.fileName(s)) }, onMessage = { message = it }, message = message, onRemoved = { removed, wasActive -> undo.offer { if (host.saveSkin(removed) && wasActive) host.useSkin(removed.id) } })
    } else Column {
        if (message.isNotBlank()) BasicText(message, Modifier.padding(vertical = Metrics.gridDp.dp * 2),
            style = settingsText(skin, skin.colors.accent, true))
        SettingsSection("Your skins", skin)
        SettingsCard(skin) {
            if (host.custom.isEmpty()) BasicText("No custom skins yet. Start from a built-in skin below.",
                Modifier.padding(Metrics.gridDp.dp * 4), style = settingsText(skin, skin.colors.textSecondary, true))
            host.custom.forEach { s ->
                SettingRow(s.name, skin, SettingKind.Page, description = if (s.id == host.activeId) "In use" else "Edit colours") {
                    model = SkinEditorModel.from(s)
                }
            }
        }
        SettingsSection("Start a new skin from", skin)
        SettingsCard(skin) {
            host.builtIn.forEach { s -> SettingRow(s.name, skin, SettingKind.Page, description = "Copy and edit") { model = SkinEditorModel.from(s) } }
        }
        SettingsSection("Share", skin)
        SettingsCard(skin) {
            SettingRow("Import a skin file", skin, SettingKind.Action, description = "A .parcskin.json file", icon = "↓") {
                importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
        }
    }
    }
    }
}

@Composable
private fun SkinEditor(
    model: SkinEditorModel, host: SkinStudioHost, skin: Skin, onModel: (SkinEditorModel?) -> Unit, onClose: () -> Unit,
    onExport: (Skin) -> Unit, onMessage: (String) -> Unit, message: String, onRemoved: (Skin, Boolean) -> Unit,
) {
    var token by remember { mutableStateOf<ColorToken?>(null) }
    val taken = (host.builtIn + host.custom)
    val editingId = remember { model.toSkin(taken.map { it.id }.toSet()).id }
    val takenNames = taken.filter { it.id != editingId }.map { it.name }
    val writerScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate) }
    val writer = remember { EditorWriter<Skin>(writerScope) { if (!host.saveSkin(it)) onMessage("Could not save the skin") } }
    LaunchedEffect(model) {
        if (model.canSave(takenNames)) writer.changed(model.copy(existingId = editingId).toSkin(emptySet()))
    }
    DisposableEffect(writer) { onDispose { writer.flush() } }
    val failing = model.failing()
    val preview = model.copy(existingId = editingId).toSkin(emptySet())
    Column(Modifier.padding(top = Metrics.gridDp.dp * 2)) {
        SkinPreview(preview)
        NameField(model.name, skin, ok = model.nameValid) { onModel(model.withName(it)) }
        if (message.isNotBlank()) BasicText(message, Modifier.padding(top = Metrics.gridDp.dp * 2), style = settingsText(skin, skin.colors.accent, true))
        if (failing.isNotEmpty()) BasicText("Low contrast (needs 4.5:1): " + failing.joinToString { "${it.token.label} ${"%.1f".format(it.ratio)}" },
            Modifier.padding(top = Metrics.gridDp.dp * 2), style = settingsText(skin, skin.colors.warn, true))
        SettingsSection("Colours", skin)
        SettingsCard(skin) {
            ColorToken.entries.forEach { t ->
                val value = t.get(model.colors)
                Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp)
                    .clickable { token = if (token == t) null else t }.padding(Metrics.gridDp.dp * 3),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp * 3)) {
                    Box(Modifier.size(28.dp).background(Color(value.toInt()), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(skin.colors.lineActive.toInt()), RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f)) {
                        BasicText(t.label, style = settingsText(skin))
                        BasicText(t.help, style = settingsText(skin, skin.colors.textSecondary, true))
                    }
                    BasicText(ColorMath.formatHex(value), style = settingsText(skin, skin.colors.textSecondary, true))
                }
                if (token == t) ColorPicker(value, t.translucent, skin) { onModel(model.withColor(t, it)) }
            }
        }
        SettingsSection("Skin", skin)
        SettingsCard(skin) {
            val canSave = model.canSave(takenNames)
            // OPUS6 (audit F7): unreadable text cannot be switched on (it can still be saved and fixed).
            val readable = model.canUse()
            if (!canSave) BasicText("Give this skin a unique name of 1 to 16 characters to save automatically.",
                style = settingsText(skin, skin.colors.warn, true))
            if (!readable) BasicText("Text and dim text must stand out from the background (at least 3:1) before this skin can be used.",
                style = settingsText(skin, skin.colors.warn, true))
            SettingRow("Use skin", skin, SettingKind.Action, description = "Apply these colours now", enabled = canSave && readable, icon = "▶") {
                writer.flush()
                val s = model.copy(existingId = editingId).toSkin(emptySet())
                if (host.saveSkin(s)) { host.useSkin(s.id); onMessage("Using ${s.name}") }
            }
            SettingRow("Reset colours", skin, SettingKind.Action, description = "Back to the base skin", enabled = model.isDirty, icon = "↺") { onModel(model.reset()) }
            if (host.custom.any { it.id == editingId }) {
                SettingRow("Export to a file", skin, SettingKind.Action, description = "Share as .parcskin.json", icon = "↑") { onExport(preview) }
                SettingRow("Remove skin", skin, SettingKind.Action, destructive = true, icon = "✕") {
                    writer.discard()
                    val removed = host.custom.firstOrNull { it.id == editingId }
                    if (host.activeId == editingId) host.useSkin(host.builtIn.first().id)
                    host.deleteSkin(editingId)
                    if (removed != null) onRemoved(removed, host.activeId == editingId)
                    onMessage("Removed ${model.name}"); onClose()
                }
            }
            SettingRow("Done editing", skin, SettingKind.Action, icon = "←") { onClose() }
        }
    }
}

@Composable
private fun NameField(name: String, skin: Skin, ok: Boolean, onName: (String) -> Unit) {
    BasicTextField(name, onName, singleLine = true, textStyle = settingsText(skin),
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        modifier = Modifier.fillMaxWidth().padding(top = Metrics.gridDp.dp * 3).heightIn(min = Metrics.touchTargetDp.dp)
            .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .border(skin.shape.surfaceBorderDp.dp, Color((if (ok) skin.colors.lineActive else skin.colors.warn).toInt()), RoundedCornerShape(Metrics.menuCornerDp.dp))
            .padding(Metrics.gridDp.dp * 4).semantics { contentDescription = "Skin name" },
        decorationBox = { inner ->
            if (name.isEmpty()) BasicText("Skin name", style = settingsText(skin, skin.colors.textSecondary))
            inner()
        })
}

/** A miniature scene (arcs, dots, aim line, hub, a stat tile, text) drawn straight from the working colours. */
@Composable
private fun SkinPreview(s: Skin) {
    val c = s.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radii = remember(density) { with(density) { floatArrayOf(34.dp.toPx(), 62.dp.toPx(), 90.dp.toPx()) } }
    val strokes = remember(density) { with(density) { arrayOf(Stroke(1.5f.dp.toPx()), Stroke(2.dp.toPx()), Stroke(1.dp.toPx())) } }
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Color(c.bg.toInt()), RoundedCornerShape(12.dp))
        .border(1.dp, Color(c.lineBase.toInt()), RoundedCornerShape(12.dp)).semantics { contentDescription = "Skin preview" }) {
        val hub = Offset(size.width - 38.dp.toPx(), size.height - 34.dp.toPx())
        radii.forEachIndexed { i, r ->
            drawArc(Color(c.lineBase.toInt()), 180f, 90f, false, Offset(hub.x - r, hub.y - r), Size(r * 2, r * 2), style = strokes[0])
            val n = 3 + i
            for (k in 0 until n) {
                val a = Math.toRadians(188.0 + 74.0 * k / (n - 1))
                val p = Offset(hub.x + (r * cos(a)).toFloat(), hub.y + (r * sin(a)).toFloat())
                val aimed = i == 1 && k == 2
                drawCircle(Color((if (aimed) c.pulse else c.accent).toInt()), if (aimed) 7.dp.toPx() else 5.5f.dp.toPx(), p)
                if (aimed) drawLine(Color(c.lineActive.toInt()), hub, p, 1.5f.dp.toPx())
            }
        }
        drawCircle(Color(c.accent2.toInt()), 12.dp.toPx(), hub, style = strokes[1])
        drawCircle(Color(c.glow.toInt()), 20.dp.toPx(), hub)
        val tile = Offset(10.dp.toPx(), 52.dp.toPx())
        drawRect(Color(c.surface.toInt()), tile, Size(92.dp.toPx(), 40.dp.toPx()))
        drawRect(Color(c.lineActive.toInt()), tile, Size(92.dp.toPx(), 40.dp.toPx()), style = strokes[2])
        drawRect(Color(c.accent.toInt()), Offset(tile.x + 8.dp.toPx(), tile.y + 26.dp.toPx()), Size(54.dp.toPx(), 5.dp.toPx()))
        drawRect(Color(c.textSecondary.toInt()), Offset(tile.x + 8.dp.toPx(), tile.y + 10.dp.toPx()), Size(30.dp.toPx(), 4.dp.toPx()))
        drawRect(Color(c.textPrimary.toInt()), Offset(10.dp.toPx(), 14.dp.toPx()), Size(64.dp.toPx(), 10.dp.toPx()))
        drawCircle(Color(c.warn.toInt()), 4.dp.toPx(), Offset(112.dp.toPx(), tile.y + 12.dp.toPx()))
        drawCircle(Color(c.danger.toInt()), 4.dp.toPx(), Offset(126.dp.toPx(), tile.y + 12.dp.toPx()))
    }
}

/** Hue, saturation, value (and alpha when [alpha]) sliders plus a hex field. Touch targets are at least 48 dp. */
@Composable
private fun ColorPicker(value: Long, alpha: Boolean, skin: Skin, onChange: (Long) -> Unit) {
    val hsv = ColorMath.toHsv(value)
    var hex by remember(value) { mutableStateOf(ColorMath.formatHex(value)) }
    Column(Modifier.padding(horizontal = Metrics.gridDp.dp * 4, vertical = Metrics.gridDp.dp * 2)) {
        Slider("Hue", hsv.hue / 360f, skin) { onChange(ColorMath.fromHsv(hsv.copy(hue = it * 359.99f))) }
        Slider("Saturation", hsv.saturation, skin) { onChange(ColorMath.fromHsv(hsv.copy(saturation = it))) }
        Slider("Brightness", hsv.value, skin) { onChange(ColorMath.fromHsv(hsv.copy(value = it))) }
        if (alpha) Slider("Opacity", hsv.alpha / 255f, skin) { onChange(ColorMath.fromHsv(hsv.copy(alpha = (it * 255f).toInt()))) }
        BasicTextField(hex, { t ->
            hex = t
            ColorMath.parseHex(t)?.let { p -> onChange(if (alpha) p else p or 0xFF000000L) }
        }, singleLine = true, textStyle = settingsText(skin), cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            modifier = Modifier.fillMaxWidth().padding(top = Metrics.gridDp.dp * 2).heightIn(min = Metrics.touchTargetDp.dp)
                .background(Color(skin.colors.surface.toInt()), RoundedCornerShape(8.dp))
                .border(1.dp, Color((if (ColorMath.parseHex(hex) != null) skin.colors.lineActive else skin.colors.warn).toInt()), RoundedCornerShape(8.dp))
                .padding(Metrics.gridDp.dp * 3).semantics { contentDescription = "Hex colour" })
    }
}

@Composable
private fun Slider(label: String, fraction: Float, skin: Skin, onFraction: (Float) -> Unit) {
    val changeFraction by rememberUpdatedState(onFraction)
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.touchTargetDp.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.gridDp.dp * 3)) {
        BasicText(label, Modifier.width(Metrics.gridDp.dp * 21), style = settingsText(skin, skin.colors.textSecondary, true))
        var width by remember { mutableStateOf(1f) }
        Box(Modifier.weight(1f).height(Metrics.touchTargetDp.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1).toFloat() }
            .pointerInput(Unit) { detectTapGestures { changeFraction((it.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); changeFraction((change.position.x / width).coerceIn(0f, 1f)) } }
            .semantics { contentDescription = "$label ${(fraction * 100).toInt()} percent" }) {
            Canvas(Modifier.fillMaxWidth().height(Metrics.touchTargetDp.dp)) {
                val y = size.height / 2
                drawLine(Color(skin.colors.lineActive.toInt()), Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
                drawLine(Color(skin.colors.accent.toInt()), Offset(0f, y), Offset(size.width * fraction, y), 4.dp.toPx())
                drawCircle(Color(skin.colors.accent.toInt()), 10.dp.toPx(), Offset(size.width * fraction, y))
            }
        }
    }
}
