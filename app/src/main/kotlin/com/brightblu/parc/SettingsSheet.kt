package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.module.frostedBackground

/**
 * Shared glass surface. The host supplies the same resolved tint and glass preference as Home. T373: [cornerDp] lets
 * a small tile use a smaller radius than a sheet or a card; the default is the skin's module corner.
 */
internal fun Modifier.settingsSurface(skin: Skin, sheet: Boolean = false, cornerDp: Float = skin.shape.moduleCornerDp): Modifier {
    val corner = cornerDp.dp
    val shape = if (sheet) RoundedCornerShape(topStart = corner, topEnd = corner) else RoundedCornerShape(corner)
    val fill = if (skin.shape.moduleStyle == ModuleStyle.frosted)
        Modifier.frostedBackground(skin, cornerDp, roundBottom = !sheet, userTint = true, settingsGlass = true, floating = sheet)
    else Modifier.background(Color(skin.colors.moduleSurface.toInt()).copy(alpha = 1f))
    return clip(shape).then(fill).border(Dp.Hairline, Color(skin.colors.lineActive.toInt()), shape)
}

/**
 * Only the space between the heading and Done scrolls, including at enlarged font scales.
 *
 * T376: this is the one shared bottom sheet for every chooser and options surface (the page sheet,
 * Wallpaper and Add to arc). It always keeps the same three things visible: the 36 x 4 dp handle at
 * the top, the orange [actionLabel] button pinned above the navigation gesture area, and the safe
 * space above that gesture area ([rememberPageInsets].bottomDp). The body scrolls between the title
 * and the action, so a long list never pushes Done off screen or under the gesture bar.
 */
@Composable
internal fun SettingsSheet(title: String, skin: Skin, onClose: () -> Unit, subtitle: String = "",
    actionLabel: String = "Done", content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt())).clickable(onClick = onClose))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .heightIn(max = maxHeight * Metrics.wallpaperSheetMaxHeightFraction)
            .settingsSurface(skin, sheet = true)
            // T390: the sheet's own children (the scroller, the switches, Done) see a touch first; whatever
            // they leave over is consumed here so a drag at the end of the list cannot reach Home behind.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .padding(bottom = rememberPageInsets().bottomDp.dp)) {
            Spacer(Modifier.align(Alignment.CenterHorizontally).padding(vertical = Metrics.itemGapDp.dp)
                .size(Metrics.handleWidthDp.dp, Metrics.handleHeightDp.dp)
                .background(Color(skin.colors.textSecondary.toInt()), RoundedCornerShape(Metrics.handleHeightDp.dp)))
            BasicText(title, Modifier.padding(horizontal = Metrics.cardInsetDp.dp).semantics { heading() },
                style = settingsText(skin).copy(fontSize = skin.typography.title.sizeSp.sp))
            if (subtitle.isNotEmpty()) {
                BasicText(subtitle, Modifier.padding(start = Metrics.cardInsetDp.dp, end = Metrics.cardInsetDp.dp,
                    top = Metrics.lineGapDp.dp),
                    style = settingsText(skin, skin.colors.textSecondary))
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.settingsRowInsetDp.dp,
                vertical = Metrics.itemGapDp.dp).heightIn(min = Metrics.touchTargetDp.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(Skin.ParcOrange.toInt()))) {
                BasicText(actionLabel, style = settingsText(skin, Skin.Midnight.colors.bg))
            }
        }
    }
}
