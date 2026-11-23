package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.ring.module.frostedBackground
import com.brightblu.parc.model.ParcCardContent
import com.brightblu.parc.model.Skin

/** The four gestures the first-run card teaches (T30). Order matches the card's wording. */
internal val HintLines = listOf(
    "Tap an icon to open it.",
    "Long-press an icon for its menu.",
    "Drag the hub to aim, then release to open.",
    "Long-press the hub to move and edit icons.",
)

/**
 * Dismissible first-run hint card (T30): shown once on Home until the user taps Got it. Text only,
 * every colour and size from [Skin] and [Metrics]. It never blocks the ring: it sits below the
 * keep-out and the popup/overlays draw over it.
 */
@Composable
internal fun FirstRunHint(skin: Skin, onDismiss: () -> Unit) {
    // T391: a sheet drawn over the screen (Page settings, Wallpaper) comes first; the tip must not cover its title.
    if (SheetHost.content != null) return
    // T411: the card is a tip, so the queue shows it on screen and the new-app bar and other tips wait.
    DisposableEffect(Unit) {
        TipSession.latch.setShowing(TipKind.GettingAround, true)
        onDispose { TipSession.latch.setShowing(TipKind.GettingAround, false) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = Metrics.hintCardTopDp.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .width(Metrics.hintCardWidthDp.dp)
                .cardSurface(skin),
        ) {
            ParcCardContent {
            BasicText(
                "Getting around",
                style = TextStyle(fontFamily = skin.textFamily, 
                    color = Color(skin.colors.textPrimary.toInt()),
                    fontSize = skin.typography.title.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.title.weight),
                    letterSpacing = skin.typography.title.trackingSp.sp,
                ),
            )
            HintLines.forEach { line ->
                BasicText(
                    line,
                    style = TextStyle(fontFamily = skin.textFamily, 
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.menu.sizeSp.sp,
                        fontWeight = FontWeight(skin.typography.menu.weight),
                        letterSpacing = skin.typography.menu.trackingSp.sp,
                    ),
                    modifier = Modifier.padding(top = Metrics.hintCardLineGapDp.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                Box(
                    Modifier
                        .height(Metrics.menuRowHeightDp.dp)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = Metrics.itemGapDp.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        "Got it",
                        style = TextStyle(fontFamily = skin.textFamily, 
                            color = Color(skin.colors.accent.toInt()),
                            fontSize = skin.typography.menu.sizeSp.sp,
                            fontWeight = FontWeight(skin.typography.menu.weight),
                        ),
                    )
                }
            }
            }
        }
    }
}

private fun Modifier.cardSurface(skin: Skin): Modifier {
    val corner = RoundedCornerShape(Metrics.hintCornerDp.dp)
    // T391: the tip floats over the clock and the hub, so it is solid: the cached frost on a glass skin, else the solid surface.
    if (skin.shape.moduleStyle == ModuleStyle.frosted) {
        return this.frostedBackground(skin, Metrics.hintCornerDp)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
    }
    return this
        .background(skin.floatingSurface(), corner)
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
}
