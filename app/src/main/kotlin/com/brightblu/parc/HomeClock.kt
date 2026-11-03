package com.brightblu.parc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.ClockDetail
import com.brightblu.parc.model.ClockStyle
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SevenSegment
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypographyTokens
import com.brightblu.parc.model.textFamily
import kotlinx.coroutines.delay

/** How a clock draws its time (DESIGN 2.2, `clock` and `clockDot`). */
internal enum class ClockFace { Hidden, Digital, Dot, Text }

internal object ClockFaces {
    /**
     * A skin with `numeralFamily = Doto` draws the time as dot-matrix; the others use the quiet `clock` role.
     * The user's clock style still wins: Thin is the stored default, so on a Doto skin it means "the skin's
     * choice", and Bold, Mono, Digital and Hidden are taken as written.
     */
    fun of(typography: TypographyTokens, style: ClockStyle): ClockFace = when {
        style == ClockStyle.Hidden -> ClockFace.Hidden
        style == ClockStyle.Digital -> ClockFace.Digital
        style == ClockStyle.Thin && typography.numeralFamily == "Doto" -> ClockFace.Dot
        else -> ClockFace.Text
    }

    /**
     * The standalone clock is shown only while the stats panel is turned off, so it never doubles the panel's
     * own Clock page. It lives on Home only (a folder uses the same corner for its title), not under search
     * and not in edit mode (the Done button sits there).
     */
    fun standaloneVisible(statsPanelOn: Boolean, clockShown: Boolean, onHome: Boolean, searchOpen: Boolean,
        editing: Boolean): Boolean = !statsPanelOn && clockShown && onHome && !searchOpen && !editing

    /** Dot-matrix numerals are art, not reading text: they ignore the system font scale. */
    fun fixedDot(typography: TypographyTokens, fontScale: Float): TypographyTokens {
        val token = typography.clockDot
        val scale = fontScale.coerceAtLeast(0.5f)
        return typography.copy(clockDot = token.copy(sizeSp = (token.sizeSp / scale).coerceAtLeast(24f)))
    }
}

/** The family of the non-dot clock, shared by the panel's Clock page and the standalone clock. */
internal fun hubClockFamily(style: ClockStyle, skin: Skin): FontFamily =
    if (style == ClockStyle.Mono) FontFamily.Monospace
    else skin.textFamily?.takeIf { skin.style.clockFamily != "monospace" }
        ?: FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create(
            android.graphics.Typeface.create(skin.style.clockFamily.ifEmpty { skin.typography.fallbackFamily }, android.graphics.Typeface.NORMAL),
            hubClockWeight(style, skin), false)))

/** Wall-clock time in millis, refreshed on every minute boundary. */
@Composable
internal fun rememberMinuteMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    return now
}

/**
 * T155: the quiet Home clock, top-left. Drawn by the caller inside the lean box, so it dims and leans away
 * with the rest of the screen while the module is touched. Date and alarm lines follow the Look settings.
 */
@Composable
internal fun HomeClock(skin: Skin, look: LookSettings, options: ClockOptions, detail: ClockDetail, topDp: Float,
    modifier: Modifier = Modifier) {
    val face = ClockFaces.of(skin.typography, look.clockStyle)
    if (face == ClockFace.Hidden) return
    val now = rememberMinuteMillis()
    val text = ClockDisplay.time(now, java.time.ZoneId.systemDefault(), options.copy(seconds = false))
    val fontScale = LocalDensity.current.fontScale
    val ink = Color((if (skin.style.header == com.brightblu.parc.model.HeaderStyle.ruled) skin.colors.accent else skin.colors.textPrimary).toInt())
    Column(modifier.padding(start = Metrics.textGutterDp.dp, top = topDp.dp)) {
        when {
            face == ClockFace.Dot -> DotNumerals(text, DotRole.clockDot, ClockFaces.fixedDot(skin.typography, fontScale), ink)
            face == ClockFace.Digital && SevenSegment.supports(text) -> DigitalHubClock(text, skin)
            else -> {
                val token = skin.typography.clock
                val family = remember(look.clockStyle, skin) { hubClockFamily(look.clockStyle, skin) }
                BasicText(text, style = TextStyle(color = ink, fontSize = token.sizeSp.sp,
                    letterSpacing = token.trackingSp.sp,
                    fontWeight = FontWeight(hubClockWeight(look.clockStyle, skin)), fontFamily = family))
            }
        }
        if (detail.hasText) {
            Spacer(Modifier.height(Metrics.clockSublineGapDp.dp))
            BasicText(detail.text, style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp, letterSpacing = skin.typography.caption.trackingSp.sp))
        }
    }
}
