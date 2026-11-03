package com.brightblu.parc.ring.module

import androidx.compose.runtime.Composable
import com.brightblu.parc.model.RingCallbacks
import com.brightblu.parc.model.RingRenderer
import com.brightblu.parc.model.RingState
import com.brightblu.parc.model.Skin

/**
 * The joystick hub with its arcs, folder snap, aim line and labels as one unit. It takes the
 * skin, the scene (layout data) and callbacks only, and imports nothing from `:app`.
 * [state] is fed by the renderer's touch stream; the rest of the screen may read only
 * [HubModuleState.touchProgress].
 */
@Composable
fun HubModule(renderer: RingRenderer, scene: RingState, skin: Skin, callbacks: RingCallbacks,
    @Suppress("UNUSED_PARAMETER") state: HubModuleState) {
    renderer.Field(scene, skin, callbacks)
}
