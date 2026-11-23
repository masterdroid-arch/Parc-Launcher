package com.brightblu.parc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * T390: a hub page is drawn inside its own card, so a bottom sheet composed there only gets the card's
 * height (the Page settings sheet came out as a clipped strip with Done on top of the options). Home draws
 * one [ScreenSheetHost] at screen level; a sheet that must float over the whole screen hands its content to
 * it with [ScreenSheet]. Without a drawn host (the settings review screen, previews) the sheet is drawn
 * where it is called, which is already full screen there.
 *
 * This is a process-wide holder, not a composition local, so Home's already very large composable does not
 * grow (the Android verifier rejects it past a register limit).
 */
internal object SheetHost {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    var attached: Boolean by mutableStateOf(false)
}

/** Home's one draw point for [ScreenSheet] content. Call it inside Home's shared composition locals. */
@Composable
internal fun ScreenSheetHost() {
    DisposableEffect(Unit) {
        SheetHost.attached = true
        onDispose { SheetHost.attached = false; SheetHost.content = null }
    }
    SheetHost.content?.invoke()
}

/** Shows [content] over the whole screen while this call is in the composition. */
@Composable
internal fun ScreenSheet(content: @Composable () -> Unit) {
    if (!SheetHost.attached) {
        content()
        return
    }
    // Every recomposition hands over the latest lambda, so the sheet follows the page's live state.
    SheetHost.content = content
    DisposableEffect(Unit) { onDispose { SheetHost.content = null } }
}
