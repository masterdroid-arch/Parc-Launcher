package com.brightblu.parc.ring.module

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.CanvasRingRenderer
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingScenes

/** Fake data only: no icons, no services. */
internal object HubPreviewData {
    private val layout = RingLayout(384f, 853f)
    private fun apps(count: Int) = List(count) {
        RingSlot(SlotPosition("source", it), Slot.App(AppId("fake/$it")), "App ${it + 1}")
    }
    fun home(): RingState = RingScenes.home(layout, apps(layout.count).mapIndexed { i, s ->
        val arc = layout.arcs.getOrNull(layout.arcIndex(i))
        if (arc == null) s else s.copy(position = SlotPosition(arc.id, layout.slotIndex(i)))
    }, clock = "09:41")
    fun folder(): RingState = RingScenes.folder(layout, Folder(FolderId("fake"), "Social"), apps(20))
}

@Composable
private fun HubPreview(scene: RingState, skin: Skin, touched: Boolean) {
    val module = remember {
        HubModuleState().also {
            if (touched) it.dispatch(HubEvent.Down(onHub = true, slot = HubUiState.NO_AIM, atMs = 0L))
        }
    }
    val renderer = remember { CanvasRingRenderer() }
    val callbacks = remember {
        object : RingCallbacks {
            override fun onTap(hit: SlotHit) = Unit
            override fun onLongPress(hit: SlotHit) = Unit
            override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
        }
    }
    Box(Modifier.size(384.dp, 853.dp)) {
        HubModule(renderer, scene, skin, callbacks, module)
    }
}

private const val DEVICE = "spec:width=384dp,height=853dp,dpi=450"

@Preview(name = "Home idle Midnight", device = DEVICE) @Composable
private fun HomeIdleMidnight() = HubPreview(HubPreviewData.home(), Skin.Midnight, false)
@Preview(name = "Home idle Dusk", device = DEVICE) @Composable
private fun HomeIdleDusk() = HubPreview(HubPreviewData.home(), Skin.Dusk, false)
@Preview(name = "Home touched Midnight", device = DEVICE) @Composable
private fun HomeTouchedMidnight() = HubPreview(HubPreviewData.home(), Skin.Midnight, true)
@Preview(name = "Home touched Dusk", device = DEVICE) @Composable
private fun HomeTouchedDusk() = HubPreview(HubPreviewData.home(), Skin.Dusk, true)
@Preview(name = "Folder Midnight", device = DEVICE) @Composable
private fun FolderMidnight() = HubPreview(HubPreviewData.folder(), Skin.Midnight, false)
@Preview(name = "Folder Dusk", device = DEVICE) @Composable
private fun FolderDusk() = HubPreview(HubPreviewData.folder(), Skin.Dusk, false)
