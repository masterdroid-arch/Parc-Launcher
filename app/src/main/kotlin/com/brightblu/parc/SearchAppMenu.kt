package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcMinTouch
import com.brightblu.parc.model.rememberPageInsets
import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.module.frostedBackground
import kotlin.math.cos
import kotlin.math.sin

/** T247: the app a search long-press menu acts on. [canUninstall] is computed by [AppActions]. */
internal data class SearchAppTarget(
    val appId: AppId,
    val label: String,
    val canUninstall: Boolean,
    /** T259: the alias the user already gave this app, shown in the sheet and edited by its own row. */
    val alias: String = "",
)

/** T247: one row of the search app menu, in the card's order. */
internal enum class SearchAppAction(val label: String) {
    AddToArc("Add to arc"),
    AddToFolder("Add to folder"),
    AppInfo("App info"),
    SetAlias("Set alias"),
    Uninstall("Uninstall"),
    Hide("Hide"),
}

/** T247: pure row rules and folder filtering for the search long-press menu. */
internal object SearchAppMenu {
    /** The five rows, with Uninstall left out when the platform cannot remove the app. */
    fun actions(canUninstall: Boolean): List<SearchAppAction> = buildList {
        add(SearchAppAction.AddToArc)
        add(SearchAppAction.AddToFolder)
        add(SearchAppAction.AppInfo)
        add(SearchAppAction.SetAlias)
        if (canUninstall) add(SearchAppAction.Uninstall)
        add(SearchAppAction.Hide)
    }

    /** Hidden folders are not offered, so a search action cannot reveal one. */
    fun folders(all: List<Folder>, hiddenFolderIds: Set<FolderId>): List<Folder> =
        all.filter { it.id !in hiddenFolderIds }
}

/** T247: one free spot on the visible Home arcs, in screen dp. */
internal data class ArcPickerSpot(val position: SlotPosition, val label: String, val xDp: Float, val yDp: Float)

/**
 * T247: turns the visible arcs and the free Home slots into drawable points and hit-tests them.
 * Pure Kotlin, so the picker's rules are tested without Compose.
 */
internal object ArcPicker {
    fun spots(layout: ParcLayout, arcs: List<ArcSpec>, rotations: Map<String, Float> = emptyMap()): List<ArcPickerSpot> {
        val shown = arcs.filter { it.layer == Layer.Home }
        if (shown.isEmpty()) return emptyList()
        val byId = shown.associateBy { it.id }
        val choices = ArcSpotChoices.free(
            layout,
            visibleArcIds = shown.mapTo(mutableSetOf()) { it.id },
            visibleCapacities = shown.associate { it.id to it.slotCapacity },
        )
        return choices.mapNotNull { choice ->
            val arc = byId[choice.position.arcId] ?: return@mapNotNull null
            if (choice.position.index !in 0 until arc.slotCapacity) return@mapNotNull null
            val occupied = layout.arcs.firstOrNull { it.arc.id == arc.id }?.slots?.keys.orEmpty()
            val radians = Math.toRadians(com.brightblu.parc.ring.RingRotation.spotAngleDeg(
                arc, choice.position.index, occupied, rotations[arc.id] ?: 0f).toDouble())
            ArcPickerSpot(
                position = choice.position,
                label = choice.label,
                xDp = arc.originXDp + cos(radians).toFloat() * arc.radiusDp,
                yDp = arc.originYDp + sin(radians).toFloat() * arc.radiusDp,
            )
        }
    }

    /** Nearest free spot under the finger, within half the standard touch target. */
    fun hit(spots: List<ArcPickerSpot>, xDp: Float, yDp: Float,
        radiusDp: Float = Metrics.touchTargetDp / 2f): SlotPosition? {
        if (!xDp.isFinite() || !yDp.isFinite() || !radiusDp.isFinite() || radiusDp <= 0f) return null
        var best: SlotPosition? = null
        var bestDistance = radiusDp * radiusDp
        for (spot in spots) {
            val dx = xDp - spot.xDp
            val dy = yDp - spot.yDp
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = spot.position
            }
        }
        return best
    }
}

/**
 * T247: the app actions opened by a long press on a search row. It is a quiet bottom sheet, the
 * same surface as the existing empty-spot and new-app pickers. "Add to folder" turns the sheet
 * into its folder list; Back returns to the actions.
 */
@Composable
internal fun SearchAppMenuSheet(
    target: SearchAppTarget,
    folders: List<Folder>,
    skin: Skin,
    onAddToArc: () -> Unit,
    onAddToFolder: (Folder) -> Unit,
    onAppInfo: () -> Unit,
    /** T259: opens the alias editor, prefilled with [SearchAppTarget.alias]. */
    onSetAlias: () -> Unit,
    onUninstall: () -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    var choosingFolder by remember(target.appId) { mutableStateOf(false) }
    BackHandler {
        if (choosingFolder) choosingFolder = false else onClose()
    }
    Box(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt()))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(top = Metrics.keepOutTopDp.dp)
                .searchActionSurface(skin)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .imePadding()
                .padding(bottom = rememberPageInsets().bottomDp.dp),
        ) {
            SearchActionHeader(
                title = if (choosingFolder) "Add to folder" else target.label,
                detail = when {
                    choosingFolder -> "Choose a folder"
                    target.alias.isNotBlank() -> "Alias: ${target.alias}"
                    else -> ""
                },
                skin = skin,
                onClose = onClose,
            )
            if (!choosingFolder) {
                SearchAppMenu.actions(target.canUninstall).forEach { action ->
                    SearchActionRow(action.label, skin, danger = action == SearchAppAction.Uninstall) {
                        when (action) {
                            SearchAppAction.AddToArc -> onAddToArc()
                            SearchAppAction.AddToFolder -> choosingFolder = true
                            SearchAppAction.AppInfo -> onAppInfo()
                            SearchAppAction.SetAlias -> onSetAlias()
                            SearchAppAction.Uninstall -> onUninstall()
                            SearchAppAction.Hide -> onHide()
                        }
                    }
                }
            } else {
                if (folders.isEmpty()) {
                    SearchActionMessage("No folders yet", skin)
                } else {
                    LazyColumn(Modifier.fillMaxWidth()
                        .heightIn(max = (Metrics.menuRowHeightDp * SearchMenuVisibleRows).dp)) {
                        items(folders, key = { it.id.value }) { folder ->
                            SearchActionRow(folder.name, skin) { onAddToFolder(folder) }
                        }
                    }
                }
                SearchActionRow("Back", skin) { choosingFolder = false }
            }
        }
    }
}

/**
 * T247: "Add to arc" shows the visible Home arcs with every free spot as a quiet accent plus.
 * Tapping a plus chooses that spot; tapping elsewhere closes the picker.
 */
@Composable
internal fun SearchArcPicker(
    target: SearchAppTarget,
    layout: ParcLayout,
    geometry: RingLayout,
    skin: Skin,
    onPick: (SlotPosition) -> Unit,
    onClose: () -> Unit,
    rotations: Map<String, Float> = emptyMap(),
) {
    BackHandler(onBack = onClose)
    val spots = remember(layout, geometry, rotations) { ArcPicker.spots(layout, geometry.arcs, rotations) }
    val density = LocalDensity.current
    val pageInsets = rememberPageInsets()
    val scale = density.density
    val accent = Color(skin.colors.accent.toInt())
    val line = Color(skin.colors.lineBase.toInt()).copy(alpha = Metrics.searchArcLineAlpha)
    Box(Modifier.fillMaxSize().background(Color(skin.colors.scrim.toInt()))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Canvas(Modifier.fillMaxSize()) {
            geometry.arcs.forEach { arc ->
                drawArc(
                    color = line,
                    startAngle = arc.startAngle,
                    sweepAngle = arc.endAngle - arc.startAngle,
                    useCenter = false,
                    topLeft = Offset(
                        (arc.originXDp - arc.radiusDp) * scale,
                        (arc.originYDp - arc.radiusDp) * scale,
                    ),
                    size = Size(arc.radiusDp * 2f * scale, arc.radiusDp * 2f * scale),
                    style = Stroke(width = Metrics.glyphStrokeDp.dp.toPx()),
                )
            }
            spots.forEach { spot ->
                val centre = Offset(spot.xDp * scale, spot.yDp * scale)
                val radius = Metrics.searchArcSpotRadiusDp.dp.toPx()
                val plus = Metrics.editPlusRadiusDp.dp.toPx()
                val stroke = Metrics.editPlusStrokeDp.dp.toPx()
                drawCircle(accent.copy(alpha = Metrics.searchArcSpotAlpha), radius, centre)
                drawCircle(accent, radius, centre, style = Stroke(width = stroke))
                drawLine(accent, Offset(centre.x - plus, centre.y), Offset(centre.x + plus, centre.y),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(accent, Offset(centre.x, centre.y - plus), Offset(centre.x, centre.y + plus),
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        // The canvas is a picture; these quiet 56 dp targets give every plus a TalkBack name and
        // the same tap result as the drawn point.
        spots.forEach { spot ->
            Box(
                Modifier.offset(
                    x = (spot.xDp - Metrics.touchTargetDp / 2f).dp,
                    y = (spot.yDp - Metrics.touchTargetDp / 2f).dp,
                )
                    .size(Metrics.touchTargetDp.dp)
                    .clickable(role = Role.Button) { ArcPicker.hit(spots, spot.xDp, spot.yDp)?.let(onPick) }
                    .semantics { contentDescription = spot.label },
            )
        }
        Column(
            Modifier.align(Alignment.TopStart)
                .padding(start = pageInsets.startDp.dp, end = pageInsets.endDp.dp, top = pageInsets.topDp.dp)
                .background(
                    Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
                    RoundedCornerShape(Metrics.menuCornerDp.dp),
                )
                .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.rowGapDp.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
                BasicText(
                    "Add ${target.label} to an arc",
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textPrimary.toInt()),
                        fontSize = skin.typography.menu.sizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.clickable(onClick = onClose).parcMinTouch()
                    .padding(horizontal = Metrics.itemGapDp.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        "Close",
                        style = TextStyle(
                            fontFamily = skin.textFamily,
                            color = Color(skin.colors.accent.toInt()),
                            fontSize = skin.typography.menu.sizeSp.sp,
                        ),
                    )
                }
            }
            BasicText(
                "Tap a + spot",
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                ),
            )
        }
    }
}

@Composable
private fun SearchActionHeader(title: String, detail: String, skin: Skin, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.touchTargetDp.dp)
            .padding(start = Metrics.screenGutterDp.dp, end = Metrics.actionEdgeGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                Modifier.semantics { heading() },
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.textPrimary.toInt()),
                    fontSize = skin.typography.menu.sizeSp.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                BasicText(
                    detail,
                    style = TextStyle(
                        fontFamily = skin.textFamily,
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp,
                    ),
                )
            }
        }
        Box(Modifier.clickable(onClick = onClose)
            .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.itemGapDp.dp)) {
            BasicText(
                "Close",
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.accent.toInt()),
                    fontSize = skin.typography.menu.sizeSp.sp,
                ),
            )
        }
    }
}

@Composable
private fun SearchActionRow(label: String, skin: Skin, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            label,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color((if (danger) skin.colors.danger else skin.colors.textPrimary).toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.menu.weight),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchActionMessage(text: String, skin: Skin) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.screenGutterDp.dp, vertical = Metrics.itemGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
            ),
        )
    }
}

private fun Modifier.searchActionSurface(skin: Skin): Modifier = then(
    if (skin.shape.moduleStyle == com.brightblu.parc.model.ModuleStyle.frosted) {
        Modifier.frostedBackground(skin, Metrics.menuCornerDp, roundBottom = false)
    } else {
        Modifier.background(
            Color(skin.colors.surface.toInt()),
            RoundedCornerShape(topStart = Metrics.menuCornerDp.dp, topEnd = Metrics.menuCornerDp.dp),
        )
    },
)

/** Folder rows shown at once in the "Add to folder" page; a count, not a size. */
private const val SearchMenuVisibleRows = 6
