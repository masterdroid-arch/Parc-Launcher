package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.ring.FeelEvent
import com.brightblu.parc.ring.FeelPlayer
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme
import kotlinx.coroutines.launch

/**
 * T80: what the Settings list needs to show the Feel row. The Settings owner builds a row from
 * these three fields and opens [FeelSettingsScreen] when it is tapped; nothing else is required.
 */
internal data class FeelSettingsEntry(val id: String, val title: String, val description: String) {
    companion object {
        val Default = FeelSettingsEntry("feel", "Feel", "Haptic presets and sound themes")

        /** The detail text for the row, e.g. `Crisp, no sound`. */
        fun summary(prefs: FeelPrefs): String =
            prefs.haptic.label + if (prefs.sound == SoundTheme.Off) ", no sound" else ", " + prefs.sound.label.lowercase()
    }
}

/** Slider steps for the sound volume, in percent. */
internal object FeelUi {
    val VolumeSteps = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
}

/**
 * T80 Feel settings, standalone: it reads and writes the app-local [FeelStore] itself, so the
 * Settings list only has to show a row and call this with the current skin and a back action.
 * Tapping a preset or theme previews it once; the volume slider previews on release of a step.
 */
@Composable
internal fun FeelSettingsScreen(skin: Skin, master: @Composable () -> Unit = {}, hapticsEnabled: Boolean = true, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { FeelStore(context) }
    val prefs by store.prefs.collectAsState(initial = FeelPrefs())
    val scope = rememberCoroutineScope()
    val player = remember { FeelPlayer() }
    val view = LocalView.current
    DisposableEffect(player) { onDispose { player.release() } }
    FeelSettingsContent(
        skin = skin,
        prefs = prefs,
        onChange = { change -> scope.launch { store.update(change) } },
        onPreview = { event, preview -> player.play(view, event, preview) },
        onBack = onBack,
        master = master,
        hapticsEnabled = hapticsEnabled,
    )
}

/** Stateless body of [FeelSettingsScreen], used directly by previews and tests of the layout. */
@Composable
internal fun FeelSettingsContent(
    skin: Skin,
    prefs: FeelPrefs,
    onChange: ((FeelPrefs) -> FeelPrefs) -> Unit,
    onPreview: (FeelEvent, FeelPrefs) -> Unit,
    onBack: () -> Unit,
    master: @Composable () -> Unit = {},
    hapticsEnabled: Boolean = true,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = Metrics.textGutterDp.dp,
                    end = Metrics.textGutterDp.dp,
                    top = Metrics.keepOutTopDp.dp,
                    bottom = Metrics.keepOutBottomDp.dp,
                ),
        ) {
            BasicText(FeelSettingsEntry.Default.title, style = feelStyle(skin.colors.textPrimary, skin.typography.title))

            master()
            if (hapticsEnabled) {
            FeelSection("Haptics", skin)
            HapticPreset.entries.forEach { preset ->
                FeelRow(preset.label, preset.detail, preset == prefs.haptic, skin) {
                    val next = prefs.copy(haptic = preset)
                    onChange { it.copy(haptic = preset) }
                    onPreview(FeelEvent.Confirm, next.copy(sound = SoundTheme.Off))
                }
            }

            }
            FeelSection("Sounds", skin)
            SoundTheme.entries.forEach { theme ->
                FeelRow(theme.label, theme.detail, theme == prefs.sound, skin) {
                    val next = prefs.copy(sound = theme)
                    onChange { it.copy(sound = theme) }
                    onPreview(FeelEvent.Tick, next.copy(haptic = HapticPreset.Off))
                }
            }
            if (prefs.sound != SoundTheme.Off) {
                FeelSlider("Volume", prefs.volumePercent, FeelUi.VolumeSteps, " %", skin) { value ->
                    onChange { it.copy(volumePercent = value) }
                    onPreview(FeelEvent.Tick, prefs.copy(volumePercent = value, haptic = HapticPreset.Off))
                }
                BasicText(
                    "Sounds stay silent when the phone is on silent or vibrate.",
                    style = feelStyle(skin.colors.textSecondary, skin.typography.caption),
                    modifier = Modifier.padding(start = Metrics.gridDp.dp, top = Metrics.gridDp.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = Metrics.keepOutTopDp.dp, end = Metrics.gridDp.dp)
                .height(Metrics.menuRowHeightDp.dp)
                .clickable(onClick = onBack)
                .padding(horizontal = Metrics.gridDp.dp * 2),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("Back", style = feelStyle(skin.colors.accent, skin.typography.menu))
        }
    }
}

@Composable
private fun FeelSection(title: String, skin: Skin) {
    BasicText(
        title,
        style = feelStyle(skin.colors.textSecondary, skin.typography.caption),
        modifier = Modifier.padding(top = Metrics.gridDp.dp * 3, bottom = Metrics.gridDp.dp),
    )
}

@Composable
private fun FeelRow(label: String, detail: String, selected: Boolean, skin: Skin, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.touchTargetDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.gridDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = feelStyle(if (selected) skin.colors.accent else skin.colors.textPrimary, skin.typography.menu),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            if (selected) "Selected" else detail,
            style = feelStyle(skin.colors.textSecondary, skin.typography.caption),
        )
    }
}

/** Drag slider over fixed steps, same behaviour as the Look sliders: emits only on a step change. */
@Composable
private fun FeelSlider(label: String, value: Int, values: List<Int>, suffix: String, skin: Skin, onChange: (Int) -> Unit) {
    val index = values.indexOf(value).let { if (it >= 0) it else values.indexOfFirst { v -> v >= value }.coerceAtLeast(0) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Metrics.touchTargetDp.dp)
                .padding(horizontal = Metrics.gridDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(label, style = feelStyle(skin.colors.textPrimary, skin.typography.menu))
            BasicText("$value$suffix", style = feelStyle(skin.colors.textSecondary, skin.typography.caption))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(Metrics.gridDp.dp * 8)
                .padding(horizontal = Metrics.gridDp.dp * 2)
                .pointerInput(values) {
                    val pad = Metrics.gridDp.dp.toPx() * 2
                    fun emit(x: Float) {
                        val span = (size.width - pad * 2).coerceAtLeast(1f)
                        val next = values[LookRules.stepIndex((x - pad) / span, values)]
                        if (next != latestValue) latestOnChange(next)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        emit(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                emit(change.position.x)
                                change.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        ) {
            val pad = Metrics.gridDp.dp.toPx() * 2
            val y = size.height / 2f
            val track = Color(skin.colors.lineActive.toInt())
            val accent = Color(skin.colors.accent.toInt())
            val span = (size.width - pad * 2).coerceAtLeast(1f)
            val last = (values.size - 1).coerceAtLeast(1)
            drawLine(track, Offset(pad, y), Offset(size.width - pad, y),
                strokeWidth = Metrics.gridDp.dp.toPx() / 2f, cap = StrokeCap.Round)
            for (i in values.indices) {
                drawCircle(if (i <= index) accent else track, radius = Metrics.gridDp.dp.toPx() / 2f,
                    center = Offset(pad + span * i / last, y))
            }
            val thumbX = pad + span * index / last
            drawCircle(accent, radius = Metrics.gridDp.dp.toPx() * 1.75f, center = Offset(thumbX, y))
            drawCircle(Color(skin.colors.bg.toInt()), radius = Metrics.gridDp.dp.toPx() * 0.75f, center = Offset(thumbX, y))
        }
    }
}

private fun feelStyle(tint: Long, token: TypeToken): TextStyle = TextStyle(
    color = Color(tint.toInt()),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
