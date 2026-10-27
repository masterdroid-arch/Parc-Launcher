package com.brightblu.parc

import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.FolderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread shell state; T09 binds feature state to this navigation boundary. */
data class ShellState(
    val layer: Layer = Layer.Home,
    val searchOpen: Boolean = false,
    val keyboardOpen: Boolean = false,
    val abcListOpen: Boolean = false,
    val folderId: FolderId? = null,
)

enum class ShellEvent { ResetToBase, Back }

class ShellNavigation(initialState: ShellState = ShellState()) {
    private val mutableState = MutableStateFlow(initialState)
    val state = mutableState.asStateFlow()

    fun show(state: ShellState) { mutableState.value = state }

    fun emit(event: ShellEvent) {
        val current = mutableState.value
        mutableState.value = when {
            event == ShellEvent.ResetToBase -> ShellState()
            current.searchOpen && current.keyboardOpen -> current.copy(keyboardOpen = false)
            current.abcListOpen -> current.copy(abcListOpen = false)
            current.searchOpen -> current.copy(searchOpen = false, keyboardOpen = false)
            else -> ShellState()
        }
    }
}
