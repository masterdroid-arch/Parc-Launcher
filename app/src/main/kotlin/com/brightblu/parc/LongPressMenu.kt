package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.ring.module.frostedBackground
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import com.brightblu.parc.model.parcPress
import com.brightblu.parc.ring.module.rememberSheetProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.brightblu.parc.core.data.notifications.PeekLine
import androidx.compose.foundation.layout.size
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlin.math.roundToInt

/** Shortcut rows shown at once; the query is already capped by the data layer, kept defensive here. */
private const val MenuShortcutLimit = 5

/** Longest folder name, mirroring LayoutRepositoryImpl and T24. */
internal const val FolderNameMaxChars = 8

/** Menu top-left position in window dp. Immutable. */
data class MenuPlacement(val xDp: Float, val yDp: Float)

/** Pages of the app long-press menu (T30, extended by T68). */
enum class MenuPage { Root, MoveToFolder, ConfirmUninstall, NewFolder }

/** Pages of the folder long-press menu (T30). */
enum class FolderPage { Root, Rename, AddApps, ConfirmDelete }

/**
 * An open app long-press menu. Immutable snapshot; shortcuts may still be empty while they load, and
 * [isDefaultHome] decides whether a hint row replaces them. Anchor coordinates are window dp.
 */
data class MenuModel(
    val appId: AppId,
    val position: SlotPosition,
    val anchorXDp: Float,
    val anchorYDp: Float,
    val shortcuts: List<AppShortcut> = emptyList(),
    val pinned: Boolean = false,
    val isDefaultHome: Boolean = true,
    val label: String = "",
    val inFolder: FolderId? = null,
    val hidden: Boolean = false,
    val page: MenuPage = MenuPage.Root,
    val folders: List<Folder> = emptyList(),
    /** Label of the pin row; an empty string hides the row because no pinned slot is free (T68). */
    val pinLabel: String = "Pin",
    /** False for a system app that has never been updated, where the platform refuses ACTION_DELETE. */
    val canUninstall: Boolean = true,
    val draftName: String = "",
    val nameIssue: String = "",
    /** T97: this app's current notifications (at most three, newest first); empty hides the section. */
    val notifications: List<PeekLine> = emptyList(),
)

/** One app with notifications inside a folder (T97). */
data class FolderNotice(val appId: AppId, val label: String, val count: Int)

/**
 * T98: lock state of a folder as its menu sees it. [None] means locking is not offered (no gate),
 * [CanLock] a folder without the flag, [Flagged] a locked folder whose unlock window is open and
 * [LockedNow] a locked folder whose apps are hidden: that menu offers nothing but the unlock row.
 */
enum class FolderMenuLock { None, CanLock, Flagged, LockedNow }

/** An open folder long-press menu (T30). Immutable snapshot; anchor coordinates are window dp. */
data class FolderMenuModel(
    val folderId: FolderId,
    val name: String,
    val position: SlotPosition,
    val anchorXDp: Float,
    val anchorYDp: Float,
    val page: FolderPage = FolderPage.Root,
    val draftName: String = name,
    val nameIssue: String = "",
    val candidates: List<AppEntry> = emptyList(),
    val canMoveEarlier: Boolean = false,
    val canMoveLater: Boolean = false,
    /** T97: the apps inside this folder that have notifications, most first; empty hides the section. */
    val notices: List<FolderNotice> = emptyList(),
    /** T98: which lock rows the menu shows. */
    val lock: FolderMenuLock = FolderMenuLock.None,
)

/** One row action in either menu. Pure identifier, so the item builders stay unit-testable. */
enum class MenuAction {
    Shortcut, TogglePin, MoveToFolder, RemoveFromFolder, Hide, AppInfo, Uninstall,
    OpenMoveToFolder, OpenUninstall, Rename, AddApps, MoveEarlier, MoveLater, Delete,
    OpenRename, OpenDelete, Save, Back, Dismiss, AddApp,
    OpenNewFolder, SaveNewFolder, CancelNewFolder, SortAZ,
    OpenNotification, DismissNotification, ClearNotifications, LaunchApp,
    LockFolder, UnlockFolder, LockNow,
}

/** A tappable row model. [detail] carries a payload id; [showDetail] hides it from the label row. */
data class MenuRowModel(val label: String, val action: MenuAction, val detail: String = "",
    val showDetail: Boolean = true)

/** One menu item: an action row or a non-interactive divider. */
sealed interface MenuItem {
    data class Row(val row: MenuRowModel) : MenuItem
    data object Divider : MenuItem
    /** T97: one notification row with its own dismiss button. */
    data class Peek(val line: PeekLine) : MenuItem
}

/** Apps of a folder that have notifications, most notifications first, then by label (T97). */
fun folderNotices(apps: List<AppEntry>, counts: Map<String, Int>): List<FolderNotice> =
    apps.mapNotNull { app ->
        val count = counts[app.packageName] ?: 0
        if (count > 0) FolderNotice(app.id, app.label, count) else null
    }.sortedWith(compareByDescending<FolderNotice> { it.count }.thenBy { it.label.lowercase() })

/** The app menu's root rows, in order. Shortcuts replace the not-default-home hint. */
fun appMenuRootItems(menu: MenuModel): List<MenuItem> {
    val items = mutableListOf<MenuItem>()
    if (menu.notifications.isNotEmpty()) {
        menu.notifications.forEach { items += MenuItem.Peek(it) }
        if (menu.notifications.size > 1) items += MenuItem.Row(MenuRowModel("Clear all", MenuAction.ClearNotifications))
    }
    val notificationRows = items.size
    if (!menu.isDefaultHome) {
        items += MenuItem.Row(MenuRowModel("Set Parc as your home screen for shortcuts", MenuAction.Dismiss))
    } else {
        menu.shortcuts.take(MenuShortcutLimit).forEach {
            items += MenuItem.Row(MenuRowModel(it.label, MenuAction.Shortcut, it.id))
        }
    }
    // The shortcut block's own divider follows; a notification section above a shortcut block needs one more.
    if (notificationRows > 0 && items.size > notificationRows) items.add(notificationRows, MenuItem.Divider)
    items += MenuItem.Divider
    if (menu.pinned) {
        items += MenuItem.Row(MenuRowModel("Unpin", MenuAction.TogglePin))
    } else if (menu.pinLabel.isNotEmpty()) {
        items += MenuItem.Row(MenuRowModel(menu.pinLabel, MenuAction.TogglePin))
    }
    items += MenuItem.Row(MenuRowModel("Move to folder", MenuAction.OpenMoveToFolder))
    if (menu.inFolder != null) {
        items += MenuItem.Row(MenuRowModel("Remove from folder", MenuAction.RemoveFromFolder))
    }
    items += MenuItem.Row(MenuRowModel("Hide from Parc", MenuAction.Hide))
    items += MenuItem.Row(MenuRowModel("App info", MenuAction.AppInfo))
    // A system app that has never been updated cannot be uninstalled, so the row would fail.
    if (menu.canUninstall) items += MenuItem.Row(MenuRowModel("Uninstall", MenuAction.OpenUninstall))
    return items
}

/** The "move to folder" page: one row per folder, then New folder, then Back. */
fun appMenuFolderItems(menu: MenuModel): List<MenuItem> =
    menu.folders.map { MenuItem.Row(MenuRowModel(it.name, MenuAction.MoveToFolder, it.id.value, showDetail = false)) } +
        MenuItem.Divider +
        MenuItem.Row(MenuRowModel("New folder", MenuAction.OpenNewFolder)) +
        MenuItem.Row(MenuRowModel("Back", MenuAction.Back))

/** The uninstall confirmation page. */
fun appMenuConfirmItems(menu: MenuModel): List<MenuItem> = listOf(
    MenuItem.Row(MenuRowModel(
        if (menu.label.isEmpty()) "Uninstall this app?" else "Uninstall ${menu.label}?",
        MenuAction.Dismiss,
    )),
    MenuItem.Divider,
    MenuItem.Row(MenuRowModel("Uninstall", MenuAction.Uninstall)),
    MenuItem.Row(MenuRowModel("Cancel", MenuAction.Back)),
)

/** The folder menu's root rows. Move rows are disabled when the folder is already at an end. */
fun folderMenuRootItems(menu: FolderMenuModel): List<MenuItem> =
    if (menu.lock == FolderMenuLock.LockedNow) listOf(
        MenuItem.Row(MenuRowModel("Unlock folder", MenuAction.UnlockFolder)),
    ) else folderNoticeItems(menu) + listOf(
        MenuItem.Row(MenuRowModel("Rename", MenuAction.OpenRename)),
        MenuItem.Row(MenuRowModel("Add apps", MenuAction.AddApps)),
        MenuItem.Row(MenuRowModel("Sort A to Z", MenuAction.SortAZ)),
        MenuItem.Row(MenuRowModel("Move earlier", MenuAction.MoveEarlier, if (menu.canMoveEarlier) "" else "At the start")),
        MenuItem.Row(MenuRowModel("Move later", MenuAction.MoveLater, if (menu.canMoveLater) "" else "At the end")),
    ) + folderLockItems(menu) + listOf(
        MenuItem.Row(MenuRowModel("Delete", MenuAction.OpenDelete)),
    )

/** T98: `Lock folder` for a plain folder; `Lock now` and `Unlock folder` for a locked one that is open. */
private fun folderLockItems(menu: FolderMenuModel): List<MenuItem> = when (menu.lock) {
    FolderMenuLock.CanLock -> listOf(MenuItem.Row(MenuRowModel("Lock folder", MenuAction.LockFolder)))
    FolderMenuLock.Flagged -> listOf(
        MenuItem.Row(MenuRowModel("Lock now", MenuAction.LockNow)),
        MenuItem.Row(MenuRowModel("Unlock folder", MenuAction.UnlockFolder)),
    )
    else -> emptyList()
}

/** T97: one row per app with notifications, then a divider; nothing at all when there are none. */
private fun folderNoticeItems(menu: FolderMenuModel): List<MenuItem> =
    if (menu.notices.isEmpty()) emptyList()
    else menu.notices.map {
        MenuItem.Row(MenuRowModel("${it.label} \u00B7 ${it.count}", MenuAction.LaunchApp, it.appId.value, showDetail = false))
    } + MenuItem.Divider

/** The add-apps page: one row per candidate, then Done. */
fun folderMenuAddItems(menu: FolderMenuModel): List<MenuItem> =
    menu.candidates.map { MenuItem.Row(MenuRowModel(it.label, MenuAction.AddApp, it.id.value, showDetail = false)) } +
        MenuItem.Divider +
        MenuItem.Row(MenuRowModel("Done", MenuAction.Dismiss))

/** The delete confirmation page. Copy is deliberate: a Pinned app stays where it is. */
fun folderMenuConfirmItems(menu: FolderMenuModel): List<MenuItem> = listOf(
    MenuItem.Row(MenuRowModel("Delete ${menu.name}?", MenuAction.Dismiss)),
    MenuItem.Row(MenuRowModel("Its apps go to New.", MenuAction.Dismiss)),
    MenuItem.Divider,
    MenuItem.Row(MenuRowModel("Delete", MenuAction.Delete)),
    MenuItem.Row(MenuRowModel("Cancel", MenuAction.Back)),
)

/**
 * Places the menu beside the anchor: to its right by default, flipping to the left near the right
 * edge, and centred vertically then clamped inside the keep-outs. Pure; the returned position never
 * leaves the safe band when the menu fits, and clamps to it when it cannot.
 */
fun placeMenu(
    anchorXDp: Float,
    anchorYDp: Float,
    menuWidthDp: Float,
    menuHeightDp: Float,
    screenWidthDp: Float,
    screenHeightDp: Float,
    gapDp: Float = Metrics.menuGapDp,
    keepOutLeftDp: Float = Metrics.keepOutLeftDp,
    keepOutTopDp: Float = Metrics.keepOutTopDp,
    keepOutRightDp: Float = Metrics.keepOutRightDp,
    keepOutBottomDp: Float = Metrics.keepOutBottomDp,
): MenuPlacement {
    val minX = keepOutLeftDp
    val maxX = screenWidthDp - keepOutRightDp - menuWidthDp
    val minY = keepOutTopDp
    val maxY = screenHeightDp - keepOutBottomDp - menuHeightDp

    val right = anchorXDp + gapDp
    val left = anchorXDp - gapDp - menuWidthDp
    val x = when {
        right + menuWidthDp <= screenWidthDp - keepOutRightDp -> right
        left >= keepOutLeftDp -> left
        else -> minX.coerceAtLeast(maxX)
    }
    // A menu taller than the safe band (a long candidate list) cannot be centred; pin it to the top
    // and let the caller scroll it, instead of coercing into an empty range.
    val y = if (maxY < minY) minY else (anchorYDp - menuHeightDp / 2f).coerceIn(minY, maxY)
    return MenuPlacement(x, y)
}

/** The home arcs that hold pinned apps: the two innermost (A1, A2). Empty slots are candidates. */
private fun ParcLayout.pinnedAssignments(): List<com.brightblu.parc.model.ArcAssignment> =
    arcs.filter { it.arc.layer == Layer.Home && it.arc.index <= 1 }

/** First vacant slot across the pinned home arcs, or null when they are full. */
fun firstPinnedVacancy(layout: ParcLayout): SlotPosition? {
    for (assignment in layout.pinnedAssignments()) {
        for (index in 0 until assignment.arc.slotCapacity) {
            if (index !in assignment.slots) return SlotPosition(assignment.arc.id, index)
        }
    }
    return null
}

/**
 * Where a "Pin" should land: the app's current home slot when it already sits on a pinned arc
 * (pin in place), otherwise the first vacant pinned slot.
 */
fun pinPosition(layout: ParcLayout, position: SlotPosition): SlotPosition? {
    val current = layout.arcs.firstOrNull { it.arc.id == position.arcId }
    if (current != null && current.arc.layer == Layer.Home && current.arc.index <= 1) return position
    return firstPinnedVacancy(layout)
}

/**
 * Label of the pin row (T68): names the arc that will receive the app. An empty label means no
 * pinned slot is free, so the caller hides the row instead of offering a pin that cannot land.
 */
fun pinRowLabel(layout: ParcLayout, position: SlotPosition): String {
    val target = pinPosition(layout, position) ?: return ""
    val arc = layout.arcs.firstOrNull { it.arc.id == target.arcId } ?: return "Pin"
    return if (arc.arc.layer == Layer.Home && arc.arc.index <= 1) "Pin to arc ${arc.arc.index + 1}" else "Pin"
}

/**
 * Whether the platform will uninstall this app (T68): every user app, and only a system app that
 * has been updated at least once. A preinstalled system app that never got an update is part of the
 * system image and the uninstall intent fails silently.
 */
fun canOfferUninstall(isSystem: Boolean, updatedSystemApp: Boolean): Boolean = !isSystem || updatedSystemApp

/**
 * Folder contents in A-to-Z order (T68). Case-insensitive on the label, then the id so the result is
 * stable when two apps share a label (two profiles of the same package, for example).
 */
fun sortFolderApps(appIds: List<AppId>, label: (AppId) -> String): List<AppId> =
    appIds.sortedWith(compareBy({ label(it).lowercase() }, { it.value }))

/** Next free folder key, so a new folder never collides with an existing one. */
fun nextFolderId(layout: ParcLayout): FolderId {
    val used = layout.folders.mapTo(mutableSetOf()) { it.id.value }
    var n = 1
    while ("f$n" in used) n++
    return FolderId("f$n")
}

/**
 * A layout with [name] added as a folder holding [appId] (T68): the app leaves every folder it was
 * in, and the new folder takes the first vacant home slot. Null when the arcs are full, so the caller
 * can say so instead of writing a folder no slot can show.
 */
fun newFolderLayout(layout: ParcLayout, name: String, appId: AppId): ParcLayout? {
    val position = firstFolderPosition(layout) ?: return null
    val id = nextFolderId(layout)
    val folders = layout.folders
        .map { if (appId in it.appIds) it.copy(appIds = it.appIds - appId) else it }
        .plus(Folder(id, name.trim(), appIds = listOf(appId)))
    val arcs = layout.arcs.map { assignment ->
        if (assignment.arc.id == position.arcId) assignment.copy(slots = assignment.slots + (position.index to Slot.Folder(id)))
        else assignment
    }
    return layout.copy(folders = folders, arcs = arcs)
}

/**
 * A folder name that the repository will accept, or an empty string when it is valid. Pure, so the
 * rename page can show the issue line without touching the repository.
 */
fun folderNameIssue(draft: String, folders: List<Folder>, folderId: FolderId): String {
    val name = draft.trim()
    return when {
        name.isEmpty() -> "Name cannot be empty"
        name.length > FolderNameMaxChars -> "At most $FolderNameMaxChars characters"
        folders.any { it.id != folderId && it.name.equals(name, ignoreCase = true) } -> "Name already used"
        else -> ""
    }
}

/** The tallest a menu may be; a longer list scrolls inside it instead of leaving the screen. */
@Composable
private fun menuBody(skin: Skin, content: @Composable () -> Unit) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // T157: a menu grows from 0.92 and fades in on `Springs.sheet`; a system animator scale of 0 shows it at once.
    val sheet = rememberSheetProgress(open = true, stiffnessScale = 1f / skin.motionScale)
    // T166: 220 dp is the least; a row whose label and hint need more (large text) widens the menu up to the screen gutters.
    Column(
        Modifier
            .graphicsLayer {
                val p = sheet.progress.coerceIn(0f, 1f)
                alpha = p
                val grown = Metrics.menuOpenFromScale + (1f - Metrics.menuOpenFromScale) * p
                scaleX = grown; scaleY = grown
            }
            .widthIn(min = Metrics.menuMinWidthDp.dp, max = (screenWidthDp - 2 * Metrics.screenGutterDp).dp)
            .width(IntrinsicSize.Max)
            .heightIn(max = (screenHeightDp - Metrics.keepOutTopDp - Metrics.keepOutBottomDp).dp)
            .menuSurface(skin)
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/**
 * The app long-press popup (DESIGN §2.3/2.4, S9, extended by T30): shortcuts, Pin/Unpin, Move to
 * folder, Remove from folder, Hide, App info and Uninstall, with the confirmation and folder-picker
 * pages. The popup owns outside-tap and Back dismissal via [Popup].
 */
@Composable
fun LongPressMenu(
    menu: MenuModel,
    skin: Skin,
    onDismiss: () -> Unit,
    onAction: (MenuAction, String) -> Unit,
) {
    val density = LocalDensity.current.density
    val provider = remember(menu.anchorXDp, menu.anchorYDp, density) {
        MenuPositionProvider(menu.anchorXDp, menu.anchorYDp, density)
    }
    val items = when (menu.page) {
        MenuPage.Root -> appMenuRootItems(menu)
        MenuPage.MoveToFolder -> appMenuFolderItems(menu)
        MenuPage.ConfirmUninstall -> appMenuConfirmItems(menu)
        // Rendered as an IME-aware name sheet by the host, like the folder rename page.
        MenuPage.NewFolder -> emptyList()
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        menuBody(skin) {
            items.forEach { item ->
                when (item) {
                    is MenuItem.Divider -> MenuDivider(skin)
                    is MenuItem.Peek -> PeekRow(
                        line = item.line,
                        skin = skin,
                        onOpen = { onAction(MenuAction.OpenNotification, item.line.key) },
                        onDismiss = { onAction(MenuAction.DismissNotification, item.line.key) },
                    )
                    is MenuItem.Row -> MenuRow(
                        row = item.row,
                        skin = skin,
                        onClick = { onAction(item.row.action, item.row.detail) },
                    )
                }
            }
        }
    }
}

/**
 * The folder long-press popup (T30): Rename, Add apps, Move earlier or later and Delete, with the
 * inline rename field and the add-apps picker. Same surface as the app menu.
 */
@Composable
fun FolderMenu(
    menu: FolderMenuModel,
    skin: Skin,
    onDismiss: () -> Unit,
    onAction: (MenuAction, String) -> Unit,
    onNameChange: (String) -> Unit,
) {
    val density = LocalDensity.current.density
    val provider = remember(menu.anchorXDp, menu.anchorYDp, density) {
        MenuPositionProvider(menu.anchorXDp, menu.anchorYDp, density)
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        menuBody(skin) {
            when (menu.page) {
                FolderPage.Root -> folderMenuRootItems(menu).forEach { item ->
                    when (item) {
                        is MenuItem.Divider -> MenuDivider(skin)
                        is MenuItem.Peek -> Unit
                        is MenuItem.Row -> MenuRow(item.row, skin) { onAction(item.row.action, item.row.detail) }
                    }
                }
                FolderPage.AddApps -> {
                    CaptionRow("Add apps to ${menu.name}", skin)
                    if (menu.candidates.isEmpty()) CaptionRow("Every app is already here", skin)
                    folderMenuAddItems(menu).forEach { item ->
                        when (item) {
                            is MenuItem.Divider -> MenuDivider(skin)
                            is MenuItem.Peek -> Unit
                            is MenuItem.Row -> MenuRow(item.row, skin) { onAction(item.row.action, item.row.detail) }
                        }
                    }
                }
                FolderPage.ConfirmDelete -> folderMenuConfirmItems(menu).forEach { item ->
                    when (item) {
                        is MenuItem.Divider -> MenuDivider(skin)
                        is MenuItem.Peek -> Unit
                        is MenuItem.Row -> MenuRow(item.row, skin) { onAction(item.row.action, item.row.detail) }
                    }
                }
                FolderPage.Rename -> Unit // Rendered as an IME-aware sheet by the host, not a popup.
            }
        }
    }
}

/**
 * OPUS8 (T133): the small Home menu after a long-press on empty space. Same surface, rows and
 * placement as the app menu; outside tap and Back close it.
 */
@Composable
internal fun HomeMenu(menu: HomeMenuModel, skin: Skin, onDismiss: () -> Unit, onRow: (HomeMenuRow) -> Unit) {
    val density = LocalDensity.current.density
    val provider = remember(menu.anchorXDp, menu.anchorYDp, density) {
        MenuPositionProvider(menu.anchorXDp, menu.anchorYDp, density)
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        menuBody(skin) {
            HomeMenuRow.entries.forEach { row ->
                MenuRow(MenuRowModel(row.label, MenuAction.Dismiss), skin) { onRow(row) }
            }
        }
    }
}

/** Positions the popup from the measured content and the anchor's window-dp coordinates. */
private class MenuPositionProvider(
    private val anchorXDp: Float,
    private val anchorYDp: Float,
    private val density: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width / density
        val height = popupContentSize.height / density
        val screenWidth = windowSize.width / density
        val screenHeight = windowSize.height / density
        val placement = placeMenu(anchorXDp, anchorYDp, width, height, screenWidth, screenHeight)
        return IntOffset((placement.xDp * density).roundToInt(), (placement.yDp * density).roundToInt())
    }
}

@Composable
private fun MenuRow(row: MenuRowModel, skin: Skin, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.menuRowHeightDp.dp)
            .parcPress(source)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = Metrics.textGutterDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            row.label,
            style = rowStyle(skin, danger = row.action == MenuAction.Uninstall || row.action == MenuAction.Delete),
            modifier = Modifier.weight(1f).padding(end = Metrics.rowGapDp.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.showDetail && row.detail.isNotEmpty()) {
            BasicText(
                row.detail,
                style = TextStyle(fontFamily = skin.textFamily,
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                    letterSpacing = skin.typography.caption.trackingSp.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** T97: a notification row, title over one line of text, with an `x` that dismisses it. */
@Composable
private fun PeekRow(line: PeekLine, skin: Skin, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.menuRowHeightDp.dp).padding(end = Metrics.actionEdgeGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = Metrics.textGutterDp.dp, top = Metrics.itemGapDp.dp, bottom = Metrics.itemGapDp.dp),
        ) {
            BasicText(
                line.title,
                style = rowStyle(skin, danger = false).copy(
                    fontSize = skin.typography.caption.sizeSp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.text.isNotEmpty()) {
                BasicText(
                    line.text,
                    style = rowStyle(skin, danger = false).copy(
                        color = Color(skin.colors.textSecondary.toInt()),
                        fontSize = skin.typography.caption.sizeSp.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier.size(Metrics.menuRowHeightDp.dp).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("x", style = rowStyle(skin, danger = false).copy(
                color = Color(skin.colors.textSecondary.toInt())))
        }
    }
}

@Composable
private fun CaptionRow(text: String, skin: Skin) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = TextStyle(fontFamily = skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.caption.weight),
                letterSpacing = skin.typography.caption.trackingSp.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun rowStyle(skin: Skin, danger: Boolean): TextStyle = TextStyle(fontFamily = skin.textFamily,
    color = Color((if (danger) skin.colors.danger else skin.colors.textPrimary).toInt()),
    fontSize = skin.typography.menu.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.menu.weight),
    letterSpacing = skin.typography.menu.trackingSp.sp,
)

@Composable
private fun MenuDivider(skin: Skin) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(skin.shape.surfaceBorderDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp)
            .background(Color(skin.colors.lineBase.toInt())),
    )
}

private fun Modifier.menuSurface(skin: Skin): Modifier {
    val corner = RoundedCornerShape(Metrics.menuCornerDp.dp)
    // T153: frosted skins draw the pre-rendered frost instead of the translucent tint; other skins are unchanged.
    if (skin.shape.moduleStyle == com.brightblu.parc.model.ModuleStyle.frosted) {
        return this.frostedBackground(skin, Metrics.menuCornerDp)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
    }
    return this
        .background(
            Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha),
            corner,
        )
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
}
