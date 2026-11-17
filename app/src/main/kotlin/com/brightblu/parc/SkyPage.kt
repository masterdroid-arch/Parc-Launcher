package com.brightblu.parc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcRowPadding
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** T189: the Sky page id. Lowercase and stable, because it is part of the saved hub format. */
internal object SkyPage {
    const val Id = "sky"
}

/** Words for TalkBack and for the cells, kept out of the composables so tests can read them. */
internal object SkyPresentation {
    fun placeLabel(data: SkyView.Data): String = data.place?.let { it.name.ifBlank { SkyPlaceText.display(it) } } ?: SkyView.SetAPlace

    fun heightNow(degrees: Double?): String? = degrees?.let {
        val whole = abs(it).roundToInt()
        if (it >= 0.0) "Sun now $whole° above the horizon" else "Sun now $whole° below the horizon"
    }

    fun arcDescription(data: SkyView.Data): String {
        val sun = data.sun ?: return "Sun path today. ${SkyView.SetAPlace}"
        return buildString {
            append("Sun path today. ")
            when (sun.state) {
                SunState.StaysUp -> append(SkyView.StaysUp)
                SunState.StaysDown -> append(SkyView.StaysDown)
                SunState.Normal -> append("Sunrise ${SkyView.clock(sun.sunriseMillis!!, data.offsetSeconds)}, sunset ${SkyView.clock(sun.sunsetMillis!!, data.offsetSeconds)}")
            }
            heightNow(data.sunHeightNow)?.let { append(". ").append(it) }
        }
    }

    fun moonDescription(data: SkyView.Data): String =
        "${data.moon.name.label}, ${data.moon.litPercent} percent lit, ${if (data.moon.waxing) "waxing" else "waning"}"
}

/** The Sky page: the place comes from the page options (typed by hand) first, else the weather home place. Nothing here uses the network. */
@Composable
internal fun SkyHubPage(skin: Skin, active: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val unlocker = com.brightblu.parc.arcade.LocalArcadeUnlocker.current
    val typed = store.skyPlace.collectAsState(initial = null).value
    val home = WeatherVariant.homePlace()
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(active) {
        if (active) while (true) {
            minute = System.currentTimeMillis() / 60_000L
            delay(30_000L)
        }
    }
    val place = typed ?: home
    val data = remember(place, minute) {
        val now = Instant.ofEpochSecond(minute * 60L)
        SkyView.build(place, now, ZoneId.systemDefault().rules.getOffset(now).totalSeconds)
    }
    val moonTap = if (active && unlocker != null) {
        { unlocker.onMoonTap(android.os.SystemClock.uptimeMillis()) }
    } else null
    Column(Modifier.fillMaxWidth().hubPageScroll()) { SkyContent(data, skin, moonTap) }
}

/**
 * The drawing: the sun cell, the golden and blue hour cell and the moon cell. Stateless. [onMoonTap] is the T211
 * shortcut: taps on the moon are reported to the arcade; the moon looks and reads exactly the same as before.
 */
@Composable
internal fun SkyContent(data: SkyView.Data, skin: Skin, onMoonTap: (() -> Unit)? = null) {
    val gap = Metrics.hudCellGapDp.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        SunCell(data, skin)
        if (data.sun != null) LightCell(data, skin)
        MoonCell(data, skin, onMoonTap)
    }
}

@Composable
private fun SunCell(data: SkyView.Data, skin: Skin) {
    val size = LocalHubSize.current
    val gap = Metrics.hudCellGapDp.dp
    HudCell(skin, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            val sun = data.sun
            if (sun == null) {
                BasicText(SkyView.SetAPlace, style = hudValueStyle(skin))
                CellLabel("Type a latitude and longitude in the page options", skin)
            } else {
                Column {
                    BasicText(SkyPresentation.placeLabel(data), style = hudValueStyle(skin), maxLines = 1)
                    data.offsetNote?.let { CellLabel(it, skin) }
                }
                SunArc(data, when (size) {
                    HubSize.Compact -> Metrics.skyArcCompactDp
                    HubSize.Standard -> Metrics.skyArcStandardDp
                    HubSize.Tall -> Metrics.skyArcTallDp
                }, skin)
                data.sunHeadline?.let { BasicText(it, style = hudValueStyle(skin, skin.colors.accent)) }
                SkyPresentation.heightNow(data.sunHeightNow)?.let { CellLabel(it, skin) }
                val offset = data.offsetSeconds
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f)) { Readout("Sunrise", sun.sunriseMillis?.let { SkyView.clock(it, offset) } ?: "—", skin) }
                    Box(Modifier.weight(1f)) { Readout("Sunset", sun.sunsetMillis?.let { SkyView.clock(it, offset) } ?: "—", skin) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.weight(1f)) { Readout("Solar noon", SkyView.clock(sun.solarNoonMillis, offset), skin) }
                    Box(Modifier.weight(1f)) {
                        Column {
                            Readout("Day length", SkyView.length(sun.dayLengthMillis), skin)
                            data.changeText?.let { CellLabel(it, skin) }
                        }
                    }
                }
            }
        }
    }
}

/** The sun's height through the local day over a horizon line; the part above the horizon is the accent, the part below is faint. */
@Composable
private fun SunArc(data: SkyView.Data, heightDp: Float, skin: Skin) {
    val above = Color(skin.colors.accent.toInt())
    val below = Color(skin.colors.lineBase.toInt()).copy(alpha = Metrics.skyBelowHorizonAlpha)
    val horizon = Color(skin.colors.lineBase.toInt())
    val mark = Color(skin.colors.textPrimary.toInt())
    Canvas(Modifier.fillMaxWidth().height(heightDp.dp).semantics { contentDescription = SkyPresentation.arcDescription(data) }) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.7f
        val top = data.curve.maxOrNull() ?: 0.0
        val perDegree = (horizonY * 0.92f) / max(top, 15.0).toFloat()
        fun yOf(elevation: Double) = (horizonY - elevation.toFloat() * perDegree).coerceIn(0f, h)
        drawLine(horizon, Offset(0f, horizonY), Offset(w, horizonY), Metrics.skyHorizonDp.dp.toPx())
        val stroke = Metrics.skyCurveStrokeDp.dp.toPx()
        val last = data.curve.size - 1
        for (i in 0 until last) {
            val mid = (data.curve[i] + data.curve[i + 1]) / 2.0
            drawLine(
                if (mid >= SkyMath.SunriseAltitude) above else below,
                Offset(w * i / last, yOf(data.curve[i])), Offset(w * (i + 1) / last, yOf(data.curve[i + 1])), stroke,
            )
        }
        val f = data.nowFraction
        val e = data.sunHeightNow
        if (f != null && e != null) drawCircle(mark, Metrics.skyNowDotDp.dp.toPx(), Offset(w * f.toFloat(), yOf(e)))
    }
}

@Composable
private fun LightCell(data: SkyView.Data, skin: Skin) {
    val sun = data.sun ?: return
    val offset = data.offsetSeconds
    val rows = listOf(
        "Morning blue hour" to sun.morningBlue,
        "Morning golden hour" to sun.morningGolden,
        "Evening golden hour" to sun.eveningGolden,
        "Evening blue hour" to sun.eveningBlue,
    )
    HudCell(skin, Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = rows.joinToString(". ") { (label, span) -> label + " " + (span?.let { SkyView.range(it, offset) } ?: "not today") }
    }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
            rows.forEach { (label, span) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CellLabel(label, skin) }
                    BasicText(SkyView.range(span, offset), style = hudValueStyle(skin), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MoonCell(data: SkyView.Data, skin: Skin, onMoonTap: (() -> Unit)?) {
    val size = LocalHubSize.current
    val gap = Metrics.hudCellGapDp.dp
    val moon = data.moon
    // Seen from the south the lit side is the other one.
    val mirror = (data.place?.latitude ?: 1.0) < 0.0
    HudCell(skin, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap * 2)) {
                MoonDisc(moon, mirror, when (size) {
                    HubSize.Compact -> Metrics.skyMoonCompactDp
                    HubSize.Standard -> Metrics.skyMoonStandardDp
                    HubSize.Tall -> Metrics.skyMoonTallDp
                }, SkyPresentation.moonDescription(data), skin, onMoonTap)
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    BasicText(moon.name.label, style = hudValueStyle(skin))
                    CellLabel("${moon.litPercent} % lit", skin)
                }
            }
            val offset = data.offsetSeconds
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.weight(1f)) { Readout("Next full moon", data.nextFullMillis?.let { SkyView.day(it, offset) } ?: "—", skin) }
                Box(Modifier.weight(1f)) { Readout("Next new moon", data.nextNewMillis?.let { SkyView.day(it, offset) } ?: "—", skin) }
            }
        }
    }
}

/**
 * The moon as a lit disc: the lit limb is a half circle on one side and the terminator is half an ellipse whose width
 * shrinks to a line at the quarters. Waxing is lit on the right as seen from the north; [mirror] flips it for the south.
 */
@Composable
private fun MoonDisc(moon: MoonNow, mirror: Boolean, sizeDp: Float, description: String, skin: Skin, onTap: (() -> Unit)? = null) {
    val lit = Color(skin.colors.textPrimary.toInt())
    val unlit = Color(skin.colors.lineBase.toInt()).copy(alpha = Metrics.skyMoonUnlitAlpha)
    val outline = Color(skin.colors.textSecondary.toInt())
    // A plain tap detector, not a clickable: the shortcut stays silent to TalkBack and leaves no ripple on the moon.
    val tap = if (onTap != null) Modifier.pointerInput(onTap) { detectTapGestures { onTap?.invoke() } } else Modifier
    Canvas(Modifier.size(sizeDp.dp).then(tap).semantics { contentDescription = description }) {
        val r = size.width / 2f - Metrics.skyMoonOutlineDp.dp.toPx()
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(unlit, r, c)
        val k = moon.litFraction.toFloat()
        val flip = (moon.waxing == mirror)
        val terminator = r * (1f - 2f * k)
        scale(if (flip) -1f else 1f, 1f, pivot = c) {
            if (k > 0.002f) {
                val path = Path().apply {
                    // Lit limb: right half circle from the top to the bottom.
                    arcTo(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, true)
                    // Terminator: half an ellipse from the bottom back to the top, bulging right for a crescent and left for a gibbous.
                    val a = abs(terminator)
                    arcTo(androidx.compose.ui.geometry.Rect(c.x - a, c.y - r, c.x + a, c.y + r), 90f, if (terminator > 0f) -180f else 180f, false)
                    close()
                }
                drawPath(path, lit)
            }
        }
        drawCircle(outline, r, c, style = Stroke(Metrics.skyMoonOutlineDp.dp.toPx()))
    }
}

/** The demo page: a made-up place (no weather, no typed place), worked out for today. */
@Composable
internal fun DemoSkyPage(skin: Skin) {
    val data = remember {
        val now = Instant.now()
        SkyView.build(SkyPlace(38.72, -9.14, "Demo place"), now, ZoneId.systemDefault().rules.getOffset(now).totalSeconds)
    }
    Column(Modifier.fillMaxWidth().hubPageScroll()) { SkyContent(data, skin) }
}

/** Page options: a latitude and longitude typed by hand, which wins over the weather home place. */
@Composable
internal fun SkyOptionChoices(skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    val scope = rememberCoroutineScope()
    val saved = store.skyPlace.collectAsState(initial = null).value
    val home = WeatherVariant.homePlace()
    var draft by remember(saved) { mutableStateOf(saved?.let { SkyPlaceText.display(it) }.orEmpty()) }
    val parsed = SkyPlaceText.parse(draft)
    SettingsCard(skin) {
        BasicText("Type a latitude and longitude, like 38.72, -9.14. Times show in this phone's time zone." +
            if (WeatherVariant.Available) " A typed place is used instead of the weather home place." else "",
            Modifier.parcRowPadding(), style = settingsText(skin))
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(40) },
            singleLine = true,
            textStyle = settingsText(skin),
            cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp).parcRowPadding().clearAndSetSemantics {
                contentDescription = "Latitude and longitude"
            },
        )
        SettingRow("Use this place", skin, SettingKind.Action, enabled = parsed != null && parsed != saved,
            description = if (draft.isNotBlank() && parsed == null) "Not a latitude and longitude" else "", icon = "+") {
            scope.launch { store.setSkyPlace(parsed) }
        }
        SettingRow(if (WeatherVariant.Available && home != null) "Use the weather home place" else "Clear the place", skin, SettingKind.Action,
            enabled = saved != null, icon = "−", divider = false) {
            draft = ""
            scope.launch { store.setSkyPlace(null) }
        }
    }
}
