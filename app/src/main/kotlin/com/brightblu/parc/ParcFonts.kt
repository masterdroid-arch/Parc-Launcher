package com.brightblu.parc

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.ParcFonts
import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.model.TypographyTokens

/** Which T141 numeral role a [DotNumerals] string uses. */
enum class DotRole {
    clockDot, countDot, bigDot;

    fun token(typography: TypographyTokens): TypeToken = when (this) {
        clockDot -> typography.clockDot
        countDot -> typography.countDot
        bigDot -> typography.bigDot
    }
}

/** One run of a string: either every character is dot-safe ([dot] true) or none is. */
data class DotRun(val text: String, val dot: Boolean)

/** Only ASCII digits and `: % .` are drawn in the dot-matrix face. Empty text is safe. */
fun isDotSafe(text: String): Boolean = text.all(::isDotChar)

private fun isDotChar(c: Char): Boolean = c in '0'..'9' || c == ':' || c == '%' || c == '.'

/** Splits [text] into maximal runs of dot-safe and other characters. Concatenating the runs gives [text]. */
fun splitDotRuns(text: String): List<DotRun> {
    val runs = ArrayList<DotRun>()
    var start = 0
    for (i in 1..text.length) {
        if (i == text.length || isDotChar(text[i]) != isDotChar(text[start])) {
            runs += DotRun(text.substring(start, i), isDotChar(text[start]))
            start = i
        }
    }
    return runs
}

/**
 * Draws [text] in the dot-matrix face for digits and `: % .`; other characters in the same string
 * fall back to Figtree for that run. With [useDot] false the whole string is Figtree.
 */
@Composable
fun DotNumerals(
    text: String,
    role: DotRole,
    typography: TypographyTokens,
    color: Color,
    modifier: Modifier = Modifier,
    useDot: Boolean = typography.numeralFamily == "Doto",
) {
    val token = role.token(typography)
    val styled: AnnotatedString = remember(text, token, useDot) {
        buildAnnotatedString {
            val dotFont = SpanStyle(fontFamily = ParcFonts.Doto, fontWeight = FontWeight(token.weight))
            for (run in if (useDot) splitDotRuns(text) else listOf(DotRun(text, false))) {
                if (run.dot) withStyle(dotFont) { append(run.text) } else append(run.text)
            }
        }
    }
    BasicText(
        styled,
        modifier,
        TextStyle(
            color = color,
            fontSize = token.sizeSp.sp,
            letterSpacing = token.trackingSp.sp,
            fontFamily = ParcFonts.Figtree,
            fontWeight = FontWeight(token.weight),
        ),
    )
}

/** Digits 0 to 9 and "12:48 87%" in all three roles. For a debug host to show; no host exists yet. */
@Composable
fun DotNumeralsSheet(typography: TypographyTokens, color: Color) {
    androidx.compose.foundation.layout.Column {
        for (role in DotRole.entries) {
            DotNumerals("01234" + "56789", role, typography, color, useDot = true)
            DotNumerals("12:48 87%", role, typography, color, useDot = true)
        }
    }
}
