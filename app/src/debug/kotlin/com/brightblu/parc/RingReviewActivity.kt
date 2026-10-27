package com.brightblu.parc

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import kotlinx.coroutines.delay

/** ADB-only fixture host for T07; real index/navigation/search wiring belongs to T09. */
class RingReviewActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val reviewSkin = when (intent.getStringExtra("skin")) {
            "phosphor" -> Phosphor.Skin
            "field-atlas-light" -> FieldAtlas.Light
            "field-atlas-dark" -> FieldAtlas.Dark
            else -> Skin.Midnight
        }
        window.statusBarColor = reviewSkin.colors.bg.toInt()
        window.navigationBarColor = reviewSkin.colors.bg.toInt()
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContent {
            val skin = if (intent.getBooleanExtra("flatDepth", false)) Skin.Midnight.copy(
                depthScale = listOf(1f, 1f, 1f, 1f), depthAlpha = listOf(1f, 1f, 1f, 1f), parallaxDp = 0f)
                else when (intent.getStringExtra("skin")) {
                    "phosphor" -> Phosphor.Skin
            "field-atlas-light" -> FieldAtlas.Light
                    "field-atlas-dark" -> FieldAtlas.Dark
                    else -> Skin.Midnight
                }
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                val layout = remember(maxWidth, maxHeight) {
                    RingLayout(maxWidth.value, maxHeight.value, options = RingLayoutOptions(
                        fineSpokes = intent.getBooleanExtra("spokes", false)))
                }
                // Fixture layout: four A1 anchors, the A2 usage ring (8 items, 5 visible),
                // seven folders on A3, the gear on A4[0] and Life on A4[1].
                // T75: `work` marks two A2 apps as managed-profile apps (briefcase badge) and
                // dims a third as a paused profile, without needing a real profile on the device.
                val workFixture = intent.getBooleanExtra("work", false)
                // T83: the old personal seed shape on this device: four A1 anchors, a full A2,
                // seven A3 folders, the gear and Life on A4, the unplaced New inbox, a link slot,
                // and 150 launchable entries for the All-apps arcs. Labels are neutral.
                val phoneFixture = intent.getBooleanExtra("phone", false)
                val phoneApps = remember {
                    val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    List(150) { i -> AppEntry(AppId("fixture/phone/$i"),
                        "${letters[i % 26]}app ${i.toString().padStart(3, '0')}", "pkg.fixture.phone.$i", false, 0L) }
                }
                val phoneLayout = remember {
                    val ids = phoneApps.map { it.id }
                    val folders = listOf(
                        Folder(FolderId("ai"), "AI", appIds = ids.take(5)),
                        Folder(FolderId("google"), "Google", appIds = ids.drop(5).take(12)),
                        Folder(FolderId("media"), "Media", appIds = ids.drop(17).take(10)),
                        Folder(FolderId("money"), "Money", appIds = ids.drop(27).take(6)),
                        Folder(FolderId("mine"), "Mine", appIds = ids.drop(33).take(7)),
                        Folder(FolderId("social"), "Social", appIds = ids.drop(40).take(9)),
                        Folder(FolderId("tools"), "Tools", appIds = ids.drop(49).take(11)),
                        Folder(FolderId("life"), "Life", appIds = ids.drop(60).take(8)),
                        Folder(FolderId("new"), "New", appIds = ids.drop(68).take(4)),
                    )
                    val arcs = listOf(
                        "A1" to 4, "A2" to 5, "A3" to 7, "A4" to 7,
                    ).map { (arcId, _) -> layout.arcs.first { it.id == arcId } }
                    ParcLayout(arcs = listOf(
                        ArcAssignment(arcs[0], (0 until 4).associateWith { Slot.App(ids[it]) }),
                        ArcAssignment(arcs[1], (0 until 8).associateWith { Slot.App(ids[4 + it]) }),
                        ArcAssignment(arcs[2], (0 until 7).associateWith { Slot.Folder(folders[it].id) }),
                        ArcAssignment(arcs[3], mapOf(
                            0 to Slot.Action(ActionKind.Settings),
                            1 to Slot.Folder(FolderId("life")),
                            3 to Slot.Link("parc://fixture", "L"),
                        )),
                    ), folders = folders)
                }
                // The fixture follows the stored `Mark work apps` value, so toggling it in Parc
                // Settings and relaunching proves the off state on the device.
                val markWorkApps = remember { ParcUiPrefs(this@RingReviewActivity).markWorkApps() }
                val home = remember(layout, phoneFixture) {
                    if (phoneFixture) LauncherScenes.build(layout, phoneLayout, phoneApps, ShellState(), "09:41") { null }
                    else {
                    val anchors = listOf("Alpha", "Bravo", "Charlie", "Delta")
                    val usage = listOf("Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima")
                    val folders = if (intent.getBooleanExtra("overflowHome", false))
                        listOf("Tools", "Media", "Work", "Life", "Games", "Social", "Shop", "News", "New")
                        else listOf("Tools", "Media", "Work", "Life", "Games", "Social", "Shop")
                    val slots = mutableListOf<RingSlot>()
                    val a = layout.arcs
                    anchors.forEachIndexed { i, label ->
                        if (i < a[0].slotCapacity) slots += RingSlot(SlotPosition(a[0].id, i), Slot.App(AppId("fixture/a1/$i")), label)
                    }
                    usage.forEachIndexed { i, label ->
                        slots += RingSlot(SlotPosition(a[1].id, i), Slot.App(AppId("fixture/a2/$i")), label,
                            enabled = !(workFixture && i == 3),
                            isWork = workFixture && markWorkApps && i <= 1)
                    }
                    folders.forEachIndexed { i, label ->
                        slots += RingSlot(SlotPosition(a[2].id, i), Slot.Folder(FolderId(label.lowercase())), label)
                    }
                    if (a[3].slotCapacity > 0) slots += RingSlot(SlotPosition(a[3].id, 0), Slot.Action(ActionKind.Settings), "Settings")
                    if (a[3].slotCapacity > 1) slots += RingSlot(SlotPosition(a[3].id, 1), Slot.Folder(FolderId("life")), "Life")
                    RingScenes.home(layout, slots, "09:41")
                    }
                }
                val ranked = remember(layout) {
                    val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
                        "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
                        "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray",
                        "Yankee", "Zulu", "Anchor", "Beacon", "Canvas", "Drift", "Ember", "Fathom")
                    names.mapIndexed { i, label -> RingSlot(SlotPosition("unused", i), Slot.App(AppId("social/$i")), label) }
                }
                // Real All-apps projection (T35): input order is scrambled, the scene must be A-Z.
                // T37: 40 entries so the outer arc (seven slots) overflows and rotates.
                // T75: with `work`, every ninth entry belongs to serial 10 and carries the badge.
                val workPaused = intent.getBooleanExtra("workPaused", false)
                val allApps = remember(layout, workFixture, workPaused, phoneFixture) {
                    if (phoneFixture) LauncherScenes.build(layout, phoneLayout, phoneApps,
                        ShellState(layer = Layer.AllApps), "09:41") { null }
                    else {
                    val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
                        "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
                        "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "Xray",
                        "Yankee", "Zulu", "Anchor", "Beacon", "Canvas", "Drift", "Ember", "Fathom",
                        "Glide", "Harbor", "Island", "Junction", "Keel", "Lantern", "Meadow", "North")
                    val apps = names.mapIndexed { i, l ->
                        val id = if (workFixture && i % 9 == 0) "fixture/all/$i@10" else "fixture/all/$i"
                        AppEntry(AppId(id), l, "pkg$i", false, 0)
                    }
                    val profiles = when {
                        workPaused -> WorkFixture.pausedProfiles
                        workFixture -> WorkFixture.profiles
                        else -> ProfileSet()
                    }
                    LauncherScenes.build(layout, ParcLayout(), apps, ShellState(layer = Layer.AllApps),
                        "09:41", profiles = profiles, markWorkApps = markWorkApps) { null }
                    }
                }
                val folder = remember(layout) { RingScenes.folder(layout, Folder(FolderId("social"), "Social"), ranked) }
                val recents = remember(layout) { RingScenes.recents(layout, ranked.take(7), emptySet()) }
                val empty = remember(layout) { RingScenes.recents(layout, emptyList(), emptySet()) }
                val swapPositions = setOf(SlotPosition(layout.arcs[1].id, 0), SlotPosition(layout.arcs[1].id, 3))
                var target by remember { mutableStateOf(when (intent.getStringExtra("scene")) {
                    "folder" -> folder; "recents" -> recents; "empty" -> empty; "allapps" -> allApps
                    "pulse" -> home.copy(pulse = setOf(AppId("fixture/a1/1")))
                    "hint" -> home.copy(searchHint = true)
                    "swap" -> home.copy(swapPulse = swapPositions)
                    "focus" -> home.copy(accessibilityFocus = home.slots[12].position)
                    "search" -> home.copy(highlight = setOf(AppId("fixture/a2/0"), AppId("fixture/a2/1")), searchOpen = true)
                    "zero" -> home.copy(highlight = emptySet(), searchOpen = true)
                    "searchEmpty" -> home.copy(searchOpen = true)
                    else -> home
                }) }
                val renderer: RingRenderer = remember { CanvasRingRenderer(intent.getBooleanExtra("allocations", false)) }
                renderer.Field(target, skin, object : RingCallbacks {
                    override fun onHubTap() { target = if (target.layer == Layer.Home) allApps else home }
                    override fun onTap(hit: SlotHit) {
                        Log.i("Parc", "T07 tap ${hit.position} ${hit.slot}")
                        if (hit.slot is Slot.Folder) target = folder
                    }
                    override fun onRotate(arcId: String, offset: Float) {
                        target = target.copy(rotations = target.rotations + (arcId to offset))
                        Log.i("Parc", "T38 rotation $arcId=$offset")
                    }
                    override fun onSearch() { target = home.copy(highlight = setOf(AppId("fixture/a2/0"), AppId("fixture/a2/1")), searchOpen = true) }
                    override fun onLongPress(hit: SlotHit) { Log.i("Parc", "T07 long press ${hit.position}") }
                    override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
                    override fun onSettled(state: RingState) {
                        target = state
                        Log.i("Parc", "T07 settled ${state.layer}")
                    }
                })
                BackHandler { target = home }
                if (intent.getBooleanExtra("loop", false)) LaunchedEffect(home) {
                    repeat(24) { delay(650); target = if (it % 2 == 0) folder else home }
                }
                // T35 swap evidence: alternate the swap set so the cross-fade replays for a recording.
                if (intent.getBooleanExtra("swaploop", false)) LaunchedEffect(home) {
                    repeat(12) { i ->
                        target = home.copy(swapPulse = if (i % 2 == 0) swapPositions else emptySet())
                        delay(900)
                    }
                }
                if (intent.getBooleanExtra("controls", false)) Column(Modifier.padding(start = Metrics.textGutterDp.dp,
                    top = (Metrics.keepOutTopDp + skin.typography.clock.sizeSp + Metrics.gridDp * 4).dp)) {
                    BasicText("T07 fixture", style = TextStyle(color = Color(skin.colors.textSecondary.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
                    Row {
                        ReviewButton("Home", skin) { target = home }
                        ReviewButton("Social", skin) { target = folder }
                        ReviewButton("Recents", skin) { target = recents }
                        ReviewButton("Empty", skin) { target = empty }
                    }
                    Row {
                        ReviewButton("Search ins", skin) { target = home.copy(highlight = setOf(AppId("fixture/a2/0"), AppId("fixture/a2/1")), searchOpen = true) }
                        ReviewButton("All apps", skin) { target = allApps }
                        ReviewButton("Hint", skin) { target = home.copy(searchHint = true) }
                        ReviewButton("Swap", skin) { target = home.copy(swapPulse = swapPositions) }
                    }
                }
            }
        }
        if (intent.getBooleanExtra("workToast", false)) {
            workProfileNotice(LaunchOutcome.ProfilePaused)?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
        applySkinSystemBars(this, when (intent.getStringExtra("skin")) {
            "phosphor" -> Phosphor.Skin
            "field-atlas-light" -> FieldAtlas.Light
            "field-atlas-dark" -> FieldAtlas.Dark
            else -> Skin.Midnight
        })
    }
}

@Composable
private fun ReviewButton(label: String, skin: Skin, onClick: () -> Unit) {
    BasicText(label, Modifier.clickable(onClick = onClick).heightIn(min = Metrics.menuRowHeightDp.dp)
        .padding(Metrics.gridDp.dp * 2), style = TextStyle(color = Color(skin.colors.textSecondary.toInt()), fontSize = skin.typography.caption.sizeSp.sp))
}
