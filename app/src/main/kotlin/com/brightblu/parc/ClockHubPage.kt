package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.*
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun rememberHubTime(): String {
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var time by remember { mutableStateOf(LocalTime.now().format(format)) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now().format(format)
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    return time
}

/** Segment paths are built when size/time changes, never inside a draw callback. */
@Composable
internal fun DigitalHubClock(time: String, skin: Skin) {
    val h = skin.typography.clock.sizeSp * Metrics.digitalClockHeightRatio * androidx.compose.ui.platform.LocalDensity.current.fontScale
    val width = time.sumOf { (if (it == ':') Metrics.digitalClockColonWidthRatio else Metrics.digitalClockDigitWidthRatio).toDouble() }.toFloat() * h + (time.length - 1) * h * Metrics.digitalClockGapRatio
    Box(Modifier.size(width.dp, h.dp).semantics { contentDescription = time }.drawWithCache {
        val path = Path()
        val height = size.height
        val stroke = height * Metrics.digitalClockStrokeRatio
        val digit = height * Metrics.digitalClockDigitWidthRatio
        val colon = height * Metrics.digitalClockColonWidthRatio
        var x = 0f
        time.forEach { ch ->
            if (ch == ':') {
                val dot = stroke * Metrics.digitalClockDotRadiusRatio
                for (y in listOf(height * .3f, height * .7f)) path.addOval(androidx.compose.ui.geometry.Rect(x + colon / 2 - dot, y - dot, x + colon / 2 + dot, y + dot))
                x += colon
            } else {
                SevenSegment.segmentsFor(ch)?.forEach { segment ->
                    val r = when (segment) {
                        ClockSegment.Top -> androidx.compose.ui.geometry.Rect(x + stroke / 2, 0f, x + digit - stroke / 2, stroke)
                        ClockSegment.Middle -> androidx.compose.ui.geometry.Rect(x + stroke / 2, height / 2 - stroke / 2, x + digit - stroke / 2, height / 2 + stroke / 2)
                        ClockSegment.Bottom -> androidx.compose.ui.geometry.Rect(x + stroke / 2, height - stroke, x + digit - stroke / 2, height)
                        ClockSegment.UpperLeft -> androidx.compose.ui.geometry.Rect(x, stroke / 2, x + stroke, height / 2)
                        ClockSegment.LowerLeft -> androidx.compose.ui.geometry.Rect(x, height / 2, x + stroke, height - stroke / 2)
                        ClockSegment.UpperRight -> androidx.compose.ui.geometry.Rect(x + digit - stroke, stroke / 2, x + digit, height / 2)
                        ClockSegment.LowerRight -> androidx.compose.ui.geometry.Rect(x + digit - stroke, height / 2, x + digit, height - stroke / 2)
                    }
                    path.addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(stroke / 2)))
                }
                x += digit
            }
            x += height * Metrics.digitalClockGapRatio
        }
        val color = Color(skin.colors.textPrimary.toInt())
        onDrawBehind { drawPath(path, color) }
    })
}
