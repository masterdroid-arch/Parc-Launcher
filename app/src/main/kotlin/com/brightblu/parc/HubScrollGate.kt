package com.brightblu.parc

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/** Tracks the currently composed panel scrollables, including the Comms timeline. */
internal class HubScrollGate {
    private val states = mutableSetOf<ScrollableState>()
    val atTop get() = states.all { !it.canScrollBackward }
    fun add(state: ScrollableState) { states.add(state) }
    fun remove(state: ScrollableState) { states.remove(state) }
}

internal val LocalHubScrollGate = staticCompositionLocalOf<HubScrollGate?> { null }

@Composable
internal fun RegisterHubScroll(state: ScrollableState) {
    val gate = LocalHubScrollGate.current
    DisposableEffect(gate, state) {
        gate?.add(state)
        onDispose { gate?.remove(state) }
    }
}

@Composable
internal fun rememberHubScrollState(): ScrollState = rememberScrollState().also { RegisterHubScroll(it) }
