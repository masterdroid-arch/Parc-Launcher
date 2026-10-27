package com.brightblu.parc

import com.brightblu.parc.model.Layer
import org.junit.Assert.*
import org.junit.Test

class ShellTest {
    @Test fun backNeverLeavesHome() {
        val navigation = ShellNavigation()
        repeat(3) { navigation.emit(ShellEvent.Back) }
        assertEquals(ShellState(), navigation.state.value)
    }

    @Test fun backClosesKeyboardThenSearchThenFolder() {
        val navigation = ShellNavigation()
        navigation.show(ShellState(Layer.Folder, searchOpen = true, keyboardOpen = true))
        navigation.emit(ShellEvent.Back)
        assertEquals(ShellState(Layer.Folder, searchOpen = true), navigation.state.value)
        navigation.emit(ShellEvent.Back)
        assertEquals(ShellState(Layer.Folder), navigation.state.value)
        navigation.emit(ShellEvent.Back)
        assertEquals(ShellState(), navigation.state.value)
        navigation.show(ShellState(Layer.Recents))
        navigation.emit(ShellEvent.Back)
        assertEquals(ShellState(), navigation.state.value)
    }

    @Test fun homeResetsEveryCombination() {
        for (layer in Layer.entries) for (search in listOf(false, true)) for (keyboard in listOf(false, true)) {
            val navigation = ShellNavigation()
            navigation.show(ShellState(layer, search, keyboard))
            navigation.emit(ShellEvent.ResetToBase)
            assertEquals(ShellState(), navigation.state.value)
        }
    }

    @Test fun crashesUseSlidingWindowAndIncludeBoundary() {
        assertEquals(listOf(0L, 30_000L, 60_000L), CrashWindow.record(listOf(0, 30_000), 60_000))
        assertEquals(listOf(30_000L, 60_001L), CrashWindow.record(listOf(0, 30_000), 60_001))
        assertEquals(listOf(1L), CrashWindow.record(listOf(10, 20), 1))
        assertEquals(listOf(2L, 3L, 4L), CrashWindow.record(listOf(1, 2, 3), 4))
    }
}
