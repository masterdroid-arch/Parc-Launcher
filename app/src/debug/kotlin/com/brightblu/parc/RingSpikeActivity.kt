package com.brightblu.parc

import android.os.Bundle
import android.util.Log
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*

/** ADB-only debug benchmark. No HOME intent filter or launcher icon. */
class RingSpikeActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        window.statusBarColor = Skin.Midnight.colors.bg.toInt()
        window.navigationBarColor = Skin.Midnight.colors.bg.toInt()
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        val allocationCheck = intent.getBooleanExtra("allocations", false)
        val animatorScale = android.provider.Settings.Global.getFloat(contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setContent {
            val skin = Skin.Midnight
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                val geometry = remember(maxWidth, maxHeight) {
                    SpikeGeometry(maxWidth.value, maxHeight.value)
                }
                var assets by remember { mutableStateOf<SpikeAssets?>(null) }
                LaunchedEffect(geometry) {
                    assets = SpikeAssets.create(skin, geometry.count)
                    Log.i("Parc", "M0 assets ready: 23 icons per level, 112px, background rasterization")
                }
                val loaded = assets
                if (loaded != null) {
                    val controller = remember { SpikeController(skin, animatorScale = animatorScale) }
                    val renderer: RingRenderer = remember(geometry, loaded) {
                        SpikeRenderer(geometry, loaded, controller, allocationCheck)
                    }
                    val state = remember(geometry, loaded) {
                        val arcs = geometry.arcs + geometry.arcs.map {
                            it.copy(id = "F${it.id}", layer = Layer.Folder,
                                folderId = FolderId("benchmark"))
                        }
                        val slots = List(geometry.count * 2) { index ->
                            val folder = index >= geometry.count
                            val local = index % geometry.count
                            RingSlot(SlotPosition((if (folder) "F" else "") +
                                geometry.arcs[geometry.arcIndices[local]].id,
                                geometry.slotIndices[local]),
                                Slot.App(AppId("benchmark/${if (folder) "folder" else "home"}$local@0")),
                                "${if (folder) "Folder" else "Home"} $local",
                                (if (folder) loaded.folder else loaded.home)[local])
                        }
                        RingState(arcs, slots, 0f)
                    }
                    val callbacks = remember {
                        object : RingCallbacks {
                            override fun onTap(hit: SlotHit) {
                                Log.i("Parc", "M0 slot tap: ${hit.position.arcId}/${hit.position.index}")
                            }
                            override fun onLongPress(hit: SlotHit) = Unit
                            override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
                        }
                    }
                    renderer.Field(state, skin, callbacks)
                    val scope = rememberCoroutineScope()
                    Column(Modifier.padding(start = Metrics.textGutterDp.dp,
                        top = Metrics.keepOutTopDp.dp)) {
                        DebugButton("Snap Home / Folder", skin) { controller.snap(scope) }
                        Spacer(Modifier.height(Metrics.gridDp.dp))
                        DebugButton("Run 10 snaps", skin) { controller.loop(scope) }
                    }
                } else {
                    BasicText("Preparing benchmark…", Modifier.padding(
                        start = Metrics.textGutterDp.dp, top = Metrics.keepOutTopDp.dp),
                        style = TextStyle(color = Color(skin.colors.textPrimary.toInt())))
                }
            }
        }
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
}

@Composable
private fun DebugButton(label: String, skin: Skin, click: () -> Unit) {
    BasicText(label,
        Modifier.background(Color(skin.colors.surface.toInt())).clickable(onClick = click)
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(Metrics.gridDp.dp * 3),
        style = TextStyle(color = Color(skin.colors.textPrimary.toInt()),
            fontSize = skin.typography.menu.sizeSp.sp))
}
