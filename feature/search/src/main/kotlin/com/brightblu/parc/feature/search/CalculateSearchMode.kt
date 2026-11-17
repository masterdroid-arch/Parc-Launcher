package com.brightblu.parc.feature.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.search.calculator.Calculation
import com.brightblu.parc.feature.search.calculator.Calculator
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.parcPress
import com.brightblu.parc.model.textFamily
import kotlinx.coroutines.delay

/** What the Calculate body shows for the text in the field. Pure. */
sealed interface CalculateView {
    /** Nothing typed yet. */
    data object Empty : CalculateView

    /** Something typed that is not (yet) a sum, or divides by zero: a calm "…", never an error. */
    data object Pending : CalculateView

    data class Result(val calculation: Calculation) : CalculateView
}

object CalculateRules {
    const val PendingText = "…"

    fun view(text: String): CalculateView {
        if (text.isBlank()) return CalculateView.Empty
        val calculation = Calculator.evaluate(text) ?: return CalculateView.Pending
        return CalculateView.Result(calculation)
    }

    /** The text Enter or a tap copies, or null when there is no result. */
    fun copyText(text: String): String? = (view(text) as? CalculateView.Result)?.calculation?.display
}

/**
 * T195: the Calculate mode. Uses the one evaluator ([Calculator]) that also answers sums inline in
 * Apps mode. The answer shows live under the text; Enter or a tap copies it and says "Copied" for a
 * moment. Works offline and needs no permission.
 */
class CalculateSearchMode : SearchMode {
    override val id = SearchModeIds.Calculate
    override val label = "Calculate"
    override val hint = "Type a sum"
    override val prefix: String? = "= "

    /** The display string last copied. "Copied" shows only while it equals the current result. */
    private var copiedValue by mutableStateOf<String?>(null)

    override fun onEnter(text: String, host: SearchModeHost): Boolean {
        val value = CalculateRules.copyText(text) ?: return false
        copy(host.context, value)
        return true
    }

    private fun copy(context: Context, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Result", value))
        copiedValue = value
    }

    @Composable
    override fun Body(text: String, skin: Skin, host: SearchModeHost) {
        val view = remember(text) { CalculateRules.view(text) }
        LaunchedEffect(copiedValue) {
            if (copiedValue != null) {
                delay(Metrics.searchCopiedNoticeMs)
                copiedValue = null
            }
        }
        val settle = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
        val answerStyle = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.accent.toInt()),
            fontSize = skin.typography.search.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.search.weight),
            letterSpacing = skin.typography.search.trackingSp.sp,
        )
        val captionStyle = TextStyle(
            fontFamily = skin.textFamily,
            color = Color(skin.colors.textSecondary.toInt()),
            fontSize = skin.typography.caption.sizeSp.sp,
            fontWeight = FontWeight(skin.typography.caption.weight),
            letterSpacing = skin.typography.caption.trackingSp.sp,
        )
        val source = remember { MutableInteractionSource() }
        val result = (view as? CalculateView.Result)?.calculation
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(spring(Springs.sheet.damping, Springs.sheet.stiffness))
                .then(
                    if (result != null) {
                        Modifier.parcPress(source).clickable(interactionSource = source, indication = null) {
                            copy(host.context, result.display)
                        }
                    } else Modifier,
                )
                .heightIn(min = Metrics.searchRowHeightDp.dp)
                .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
        ) {
            AnimatedContent(
                targetState = view,
                transitionSpec = { fadeIn(settle) togetherWith fadeOut(settle) },
                label = "Calculate answer",
            ) { shown ->
                when (shown) {
                    CalculateView.Empty -> BasicText("Try 15% of 80, or 200 + 15%", style = captionStyle)
                    CalculateView.Pending -> BasicText(CalculateRules.PendingText, style = answerStyle.copy(color = Color(skin.colors.textSecondary.toInt())))
                    is CalculateView.Result -> BasicText(
                        "= ${shown.calculation.display}",
                        style = answerStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (result != null) {
                BasicText(
                    if (copiedValue == result.display) "Copied" else "Tap or press Enter to copy",
                    style = captionStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
