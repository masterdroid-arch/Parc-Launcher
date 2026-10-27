package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker controller: the store is the source of truth, an unknown persisted id resolves to the
 * default, and every tap writes through the store (nothing is UI-only state). The controller collects
 * in a real [Dispatchers.Unconfined] scope, so each call is applied before the next assertion runs.
 */
class SettingsControllerTest {

    private val skins = listOf(Skin.Midnight, Skin.Dusk)

    private class FakeStore(initial: ParcSettings = ParcSettings()) : SettingsStore {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<ParcSettings> = state
        val skinWrites = mutableListOf<String>()
        val hapticWrites = mutableListOf<HapticsLevel>()
        val clockWrites = mutableListOf<Boolean>()

        override suspend fun setSkinId(id: String) {
            skinWrites += id
            state.value = state.value.copy(skinId = id)
        }

        override suspend fun setHaptics(level: HapticsLevel) {
            hapticWrites += level
            state.value = state.value.copy(haptics = level)
        }

        override suspend fun setClockVisible(visible: Boolean) {
            clockWrites += visible
            state.value = state.value.copy(clockVisible = visible)
        }

        fun push(snapshot: ParcSettings) {
            state.value = snapshot
        }
    }

    private fun controller(store: SettingsStore): Pair<SettingsController, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return SettingsController(store, skins, scope) to scope
    }

    @Test
    fun `a fresh install shows the default skin with the documented defaults`() {
        val (controller, scope) = controller(FakeStore())

        assertEquals("midnight", controller.state.value.selectedSkinId)
        assertEquals(Skin.Midnight, controller.state.value.selectedSkin)
        assertEquals(HapticsLevel.Light, controller.state.value.haptics)
        assertEquals(true, controller.state.value.clockVisible)
        scope.cancel()
    }

    @Test
    fun `selecting a skin updates the state and persists it`() {
        val store = FakeStore()
        val (controller, scope) = controller(store)

        controller.selectSkin("dusk")

        assertEquals("dusk", controller.state.value.selectedSkinId)
        assertEquals(listOf("dusk"), store.skinWrites)
        scope.cancel()
    }

    @Test
    fun `an unknown skin id resolves to the default and is what gets persisted`() {
        val store = FakeStore(ParcSettings(skinId = "neon"))
        val (controller, scope) = controller(store)

        assertEquals("midnight", controller.state.value.selectedSkinId)

        controller.selectSkin("also-not-a-skin")
        assertEquals(listOf("midnight"), store.skinWrites)
        scope.cancel()
    }

    @Test
    fun `haptics and clock taps persist and survive the next snapshot`() {
        val store = FakeStore()
        val (controller, scope) = controller(store)

        controller.selectHaptics(HapticsLevel.Strong)
        controller.setClockVisible(false)

        assertEquals(listOf(HapticsLevel.Strong), store.hapticWrites)
        assertEquals(listOf(false), store.clockWrites)
        assertEquals(HapticsLevel.Strong, controller.state.value.haptics)
        assertEquals(false, controller.state.value.clockVisible)
        scope.cancel()
    }

    @Test
    fun `an external change of the stored settings is reflected without a tap`() {
        val store = FakeStore()
        val (controller, scope) = controller(store)

        store.push(ParcSettings(skinId = "dusk", haptics = HapticsLevel.Off, clockVisible = false))

        assertEquals("dusk", controller.state.value.selectedSkinId)
        assertEquals(HapticsLevel.Off, controller.state.value.haptics)
        assertEquals(false, controller.state.value.clockVisible)
        assertEquals(emptyList<String>(), store.skinWrites)
        scope.cancel()
    }
}
