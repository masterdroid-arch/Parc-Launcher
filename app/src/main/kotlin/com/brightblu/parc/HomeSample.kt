package com.brightblu.parc

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LayoutCounts
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** Which part of Home a zoomed pane of the sample shows. */
internal enum class HomeCrop { Arcs, Clock }

/** What a sample shows beside its whole-Home thumbnail: the arcs, or the clock above the arcs. */
internal enum class HomeSampleFocus(val crops: List<HomeCrop>) {
    Arcs(listOf(HomeCrop.Arcs)), ClockAndArcs(listOf(HomeCrop.Clock, HomeCrop.Arcs))
}

internal data class HomeCropRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Pure rules of the Home sample, kept out of Compose for tests. All values are Home dp. */
internal object HomeSampleRules {
    /** The sample is taller when it holds two crops, so each crop can hold its lines. */
    fun heightDp(focus: HomeSampleFocus): Float =
        if (focus.crops.size > 1) Metrics.settingsSampleTwoCropHeightDp else Metrics.settingsSampleHeightDp

    /** The thumbnail keeps the one-crop proportions however tall the sample is. */
    fun thumbWidthDp(sampleHeightDp: Float): Float =
        sampleHeightDp * Metrics.settingsSampleThumbWidthDp / Metrics.settingsSampleHeightDp

    /**
     * The part of Home a pane of [paneWidthDp] × [paneHeightDp] shows: [Metrics.settingsSampleCropWidthDp] wide, the pane's
     * shape, always inside Home. Arcs sit at the thumb corner (the side the arc origin is on); the clock sits at the stats
     * card's top-left, just under its tab strip.
     */
    fun crop(kind: HomeCrop, homeWidthDp: Float, homeHeightDp: Float, panelTopDp: Float, originXDp: Float,
        paneWidthDp: Float, paneHeightDp: Float): HomeCropRect {
        val width = minOf(Metrics.settingsSampleCropWidthDp, homeWidthDp)
        val height = minOf(width * paneHeightDp / paneWidthDp.coerceAtLeast(1f), homeHeightDp)
        val left: Float
        val top: Float
        when (kind) {
            HomeCrop.Arcs -> {
                left = if (originXDp > homeWidthDp / 2f) homeWidthDp - width else 0f
                top = homeHeightDp - Metrics.settingsSampleArcsBottomInsetDp - height
            }
            HomeCrop.Clock -> {
                left = Metrics.panelGutterDp
                top = panelTopDp + Metrics.settingsSampleClockTopDp
            }
        }
        return HomeCropRect(left.coerceIn(0f, homeWidthDp - width), top.coerceIn(0f, homeHeightDp - height), width, height)
    }
}

/**
 * T374 (mockups 02, 11, 12): the one Home sample every look page shows. A glass card with a whole-Home thumbnail on the
 * left and one or two zoomed crops of the same Home beside it, drawn from the values being edited. [skin] is the skin
 * to draw (the skin page passes the one being previewed); [glassSkin] frames the card like Home's hub. Touches are swallowed,
 * or [onClick] opens a larger preview.
 */
@Composable
internal fun HomeSample(services: LauncherServices, skin: Skin, glassSkin: Skin, look: LookSettings, focus: HomeSampleFocus,
    counts: LayoutCounts, aligned: Boolean, mirrored: Boolean, label: String = "Live preview",
    appearance: HubAppearance? = null, onClick: (() -> Unit)? = null) {
    val raster = rememberHomeRaster(services, skin, look, counts, aligned, mirrored, appearance)
    val inner = RoundedCornerShape(Metrics.settingsSampleCornerDp.dp)
    Column(Modifier.fillMaxWidth().padding(top = Metrics.rowGapDp.dp).settingsSurface(glassSkin).padding(Metrics.cardInsetDp.dp)
        .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
        BasicText(label, style = settingsText(glassSkin).copy(fontSize = glassSkin.typography.section.sizeSp.sp,
            fontWeight = FontWeight(glassSkin.typography.section.weight)), maxLines = 1)
        val height = HomeSampleRules.heightDp(focus)
        Box(Modifier.fillMaxWidth().height(height.dp)) {
            val current = raster.ring
            if (current == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Preparing preview…", style = settingsText(glassSkin, glassSkin.colors.textSecondary, true))
            } else CompositionLocalProvider(LocalHubLook provides look) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Metrics.settingsSampleGapDp.dp)) {
                    SamplePane(Modifier.width(HomeSampleRules.thumbWidthDp(height).dp).fillMaxHeight(), inner, glassSkin) {
                        SkinHome(skin, services, raster.scene, current, look, raster.appearance)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Metrics.settingsSampleGapDp.dp)) {
                        focus.crops.forEach { crop ->
                            SamplePane(Modifier.weight(1f).fillMaxWidth(), inner, glassSkin) {
                                SkinHome(skin, services, raster.scene, current, look, raster.appearance, crop)
                            }
                        }
                    }
                }
            }
            Box(Modifier.matchParentSize().then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }))
        }
    }
}

@Composable
private fun SamplePane(modifier: Modifier, shape: RoundedCornerShape, skin: Skin, content: @Composable () -> Unit) {
    Box(modifier.clip(shape).border(Dp.Hairline, Color(skin.colors.lineActive.toInt()), shape)) { content() }
}
