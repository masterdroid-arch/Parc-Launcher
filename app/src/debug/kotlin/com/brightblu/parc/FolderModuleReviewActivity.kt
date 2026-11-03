package com.brightblu.parc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import com.brightblu.parc.ring.module.*

/** T156 reproducible demo-only folder sizes, through the production scene builder and renderer. */
class FolderModuleReviewActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DemoMode.enabled) { finish(); return }
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        setContent {
            val skin = remember { if (intent.getStringExtra("skin") == "moss")
                assets.open("skins/moss.json").bufferedReader().use { SkinJson.decode(it.readText()) }
                else Skin.Midnight }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val geometry = remember(maxWidth, maxHeight) { RingLayout(maxWidth.value, maxHeight.value) }
                val apps = remember { DemoHomeLayout.appsIfDemo(true).take(intent.getIntExtra("count", 8).coerceIn(0, 20)) }
                val folder = remember { Folder(FolderId("t156"), "Essentials", appIds = apps.map { it.id }) }
                val layout = remember(geometry) { DemoHomeLayout.sample(geometry.arcs).let { base ->
                    base.copy(folders = base.folders + folder, arcs = base.arcs.mapIndexed { i, arc ->
                        if (i == 0) arc.copy(slots = arc.slots + (0 to Slot.Folder(folder.id))) else arc
                    })
                } }
                val allApps = remember { DemoHomeLayout.appsIfDemo(true) }
                fun scene(open: Boolean) = LauncherScenes.build(geometry, layout, allApps,
                    ShellState(layer = if (open) Layer.Folder else Layer.Home, folderId = if (open) folder.id else null), "",
                    rank = { apps.map { it.id } }) { id ->
                    com.brightblu.parc.core.data.demo.DemoIcons.iconFor(true, id, Metrics.iconBitmapPx, DemoHomeLayout.labelFor(id))
                }
                val home = remember(geometry) { scene(false) }
                val opened = remember(geometry) { scene(true) }
                var target by remember { mutableStateOf(home) }
                val module = remember { HubModuleState() }
                val renderer = remember { CanvasRingRenderer(module = module, arcFeel = { ArcFeel(magnify = Magnify.DockLike) }) }
                HubModule(renderer, target, skin, object : RingCallbacks {
                    override fun onTap(hit: SlotHit) { if (hit.slot is Slot.Folder) target = opened }
                    override fun onHubTap() { target = if (target.layer == Layer.Home) opened else home }
                    override fun onRotate(arcId: String, offset: Float) { target = target.copy(rotations = target.rotations + (arcId to offset)) }
                    override fun onSearch() = Unit
                    override fun onLongPress(hit: SlotHit) = Unit
                    override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
                    override fun onSettled(state: RingState) { target = state }
                }, module)
                BackHandler { target = home }
            }
        }
    }
}
