package com.brightblu.parc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Springs
import com.brightblu.parc.model.textFamily

/**
 * T158 (DESIGN 3.4): the hub's tabs, one pill per visible page. The selected pill is `surface` with a share of
 * `accent`; it slides to the new tab on `Springs.sheet` (read only in the draw lambda), and the strip scrolls
 * the same way to keep it in view. With the system animator scale at 0 both jump. Pills are half the 48 dp
 * touch row, so their corner is half their height.
 */
@Composable
internal fun HubTabStrip(pages: List<String>, selected: String?, skin: Skin, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    val starts = remember(pages) { FloatArray(pages.size) }
    val widths = remember(pages) { FloatArray(pages.size) }
    val tallest = remember(pages) { FloatArray(1) } // pill height in px: grows with the text size
    var measured by remember(pages) { mutableIntStateOf(0) }
    val slideX = remember { Animatable(0f) }
    val slideW = remember { Animatable(0f) }
    var placed by remember { androidx.compose.runtime.mutableStateOf(false) }
    val fill = remember(skin) { Color(HubTabs.selectedFill(skin.colors.surface, skin.colors.accent).toInt()) }
    val index = pages.indexOf(selected)
    BoxWithConstraints(modifier.fillMaxWidth().height(Metrics.menuRowHeightDp.dp)) {
        val viewPx = maxWidth.value * density
        LaunchedEffect(index, measured, viewPx) {
            if (index < 0 || starts.size != pages.size || widths[index] <= 0f) return@LaunchedEffect
            val spec = spring<Float>(Springs.sheet.damping, Springs.sheet.stiffness)
            val target = HubTabs.scrollTarget(starts[index], widths[index], scroll.value.toFloat(), viewPx)
            if (!placed) {
                placed = true
                slideX.snapTo(starts[index]); slideW.snapTo(widths[index])
                scroll.scrollTo(HubTabs.scrollTarget(starts[index], widths[index], 0f, viewPx).toInt())
            } else {
                coroutineScope {
                    launch { slideX.animateTo(starts[index], spec) }
                    launch { slideW.animateTo(widths[index], spec) }
                    launch { scroll.animateScrollTo(target.toInt(), spec) }
                }
            }
        }
        val minPill = Metrics.hubTabPillHeightDp * density
        Row(Modifier.horizontalScroll(scroll).drawBehind {
            if (slideW.value > 0f) {
                val pillHeight = maxOf(minPill, tallest[0])
                val top = (size.height - pillHeight) / 2f
                drawRoundRect(fill, Offset(slideX.value, top), Size(slideW.value, pillHeight), CornerRadius(pillHeight / 2f))
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { i, id ->
                val isSelected = id == selected
                Box(Modifier.height(Metrics.menuRowHeightDp.dp)
                    .semantics { contentDescription = "${hubPageName(id)} tab${if (isSelected) ", selected" else ""}" }
                    .clickable(role = Role.Tab) { onSelect(id) }
                    .padding(horizontal = (Metrics.hubTabGapDp / 2f).dp)
                    .onGloballyPositioned {
                        val x = it.positionInParent().x
                        val w = it.size.width.toFloat()
                        if (starts[i] != x || widths[i] != w) { starts[i] = x; widths[i] = w; measured++ }
                    },
                    contentAlignment = Alignment.Center) {
                  // The pill itself: half the touch row, taller only when the text is.
                  Box(Modifier.heightIn(min = Metrics.hubTabPillHeightDp.dp).padding(horizontal = Metrics.hubTabPillPadDp.dp)
                    .onGloballyPositioned {
                        val h = it.size.height.toFloat()
                        if (tallest[0] < h) { tallest[0] = h; measured++ }
                    }, contentAlignment = Alignment.Center) {
                    BasicText(hubPageName(id), maxLines = 1, overflow = TextOverflow.Clip, style = TextStyle(
                        fontFamily = skin.textFamily, fontSize = Metrics.hubTabNameSp.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = Color((if (isSelected) skin.colors.textPrimary else skin.colors.textSecondary).toInt())))
                  }
                }
            }
        }
    }
}
