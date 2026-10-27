package com.brightblu.parc

/**
 * T136: the Quick toggles hub page, framework-free part. Every tile does only what Android allows
 * an app without a special permission: the torch and Do Not Disturb (with the access Focus already
 * asks for) switch in place; the others open the matching system panel or settings screen, because
 * Android 13 and later do not let apps switch them. Tile ids are part of the hub config format.
 */
internal object QuickToggleCatalog {
    const val PageId = "quicktoggles"
    val tiles = listOf(
        "torch", "do not disturb", "wi-fi", "bluetooth", "mobile data",
        "airplane mode", "auto-rotate", "battery saver", "screen timeout",
    )
    private val labels = mapOf(
        "torch" to "Torch", "do not disturb" to "Do Not Disturb", "wi-fi" to "Wi-Fi",
        "bluetooth" to "Bluetooth", "mobile data" to "Mobile data", "airplane mode" to "Airplane mode",
        "auto-rotate" to "Auto-rotate", "battery saver" to "Battery saver", "screen timeout" to "Screen timeout",
    )
    fun label(tile: String): String = labels[tile] ?: tile.replaceFirstChar { it.uppercase() }

    /** One line under the grid at Tall size, so the reason is said once and not in a dialog. */
    const val PlatformNote = "Android 13 and later do not let apps switch Wi-Fi, Bluetooth, mobile data, " +
        "airplane mode or auto-rotate. Those tiles open the system panel or settings screen."
}

/** Shared tile names for the options sheet: Quick toggles uses product spellings (Wi-Fi). */
internal fun hubTileLabel(page: String, tile: String): String =
    if (page == QuickToggleCatalog.PageId) QuickToggleCatalog.label(tile) else tile.replaceFirstChar { it.uppercase() }

internal enum class TorchState { Off, On, Busy, Missing }

/** The result of one torch call, mapped from the platform's exceptions by the Android adapter. */
internal enum class TorchResult { Done, Busy, Missing }

/** The camera calls the torch tile needs. The Android implementation never throws. */
internal interface TorchHardware {
    /** The id of a camera with a flash unit (back camera first), or null when there is none. */
    fun flashCameraId(): String?
    fun setTorch(cameraId: String, on: Boolean): TorchResult
}

internal object TorchLogic {
    /**
     * What a tap leads to. A phone without a flash stays Missing; a busy camera (another app is
     * using it) is tried again, since it may have been released; on failure the state says why.
     */
    fun toggle(hardware: TorchHardware, current: TorchState): TorchState {
        val id = hardware.flashCameraId() ?: return TorchState.Missing
        val target = current != TorchState.On
        return when (hardware.setTorch(id, target)) {
            TorchResult.Done -> if (target) TorchState.On else TorchState.Off
            TorchResult.Busy -> TorchState.Busy
            TorchResult.Missing -> TorchState.Missing
        }
    }

    /** Applies a torch callback. Events for another camera do not change the tile. */
    fun onCallback(state: TorchState, ourId: String?, eventId: String, enabled: Boolean?): TorchState = when {
        ourId == null -> TorchState.Missing
        eventId != ourId -> state
        enabled == null -> TorchState.Busy
        enabled -> TorchState.On
        else -> TorchState.Off
    }
}

/** Readings Parc can take without any new permission. Null means Android did not tell us. */
internal data class QuickToggleInputs(
    val torch: TorchState = TorchState.Off,
    val dndAccess: Boolean = false,
    val dndOn: Boolean? = null,
    val wifiOn: Boolean? = null,
    val bluetoothOn: Boolean? = null,
    val airplaneOn: Boolean? = null,
    val autoRotateOn: Boolean? = null,
    val batterySaverOn: Boolean? = null,
    val screenTimeoutMs: Long? = null,
) {
    companion object {
        /** Demo mode: fixed sample states, never read from the device. */
        val Demo = QuickToggleInputs(
            torch = TorchState.Off, dndAccess = true, dndOn = true, wifiOn = true, bluetoothOn = false,
            airplaneOn = false, autoRotateOn = true, batterySaverOn = false, screenTimeoutMs = 60_000,
        )
    }
}

/** The system screens the panel tiles open. The Android side maps each to an intent. */
internal enum class SystemScreen { WifiPanel, BluetoothSettings, InternetPanel, AirplaneSettings, DisplaySettings, BatterySaverSettings }

internal sealed interface QuickAction {
    data object ToggleTorch : QuickAction
    data object ToggleDnd : QuickAction
    data object AllowDnd : QuickAction
    data class Open(val screen: SystemScreen) : QuickAction
}

/**
 * What one tile shows. [on] drives the accent colour (null: no on/off reading). [hint] is the short
 * line on the tile; [description] is the full sentence TalkBack reads.
 */
internal data class QuickTile(
    val id: String,
    val label: String,
    val state: String,
    val on: Boolean?,
    val hint: String,
    val description: String,
    val action: QuickAction,
)

internal object QuickToggleTiles {
    private const val PanelHint = "Opens panel"
    private const val SettingsHint = "Opens settings"
    private const val NotAllowed = "Android does not let apps switch this, so the tile opens"

    fun onOff(value: Boolean?): String = when (value) { true -> "On"; false -> "Off"; null -> "Not shown" }

    /** "30 s", "2 min", "1 h"; an unreadable value is "Not shown". */
    fun timeout(ms: Long?): String = when {
        ms == null || ms <= 0 -> "Not shown"
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000} h"
    }

    fun tile(id: String, inputs: QuickToggleInputs): QuickTile? {
        val label = QuickToggleCatalog.label(id)
        return when (id) {
            "torch" -> {
                val state = when (inputs.torch) {
                    TorchState.On -> "On"; TorchState.Off -> "Off"
                    TorchState.Busy -> "Camera in use"; TorchState.Missing -> "No torch"
                }
                val hint = when (inputs.torch) {
                    TorchState.Missing -> "This phone has no torch"
                    TorchState.Busy -> "Close the camera app, then tap"
                    else -> "Tap to switch"
                }
                QuickTile(id, label, state, inputs.torch == TorchState.On, hint, "$label: $state. $hint.", QuickAction.ToggleTorch)
            }
            "do not disturb" -> if (inputs.dndAccess) {
                val state = onOff(inputs.dndOn)
                QuickTile(id, label, state, inputs.dndOn, "Tap to switch", "$label: $state. Tap to switch.", QuickAction.ToggleDnd)
            } else {
                val state = onOff(inputs.dndOn)
                QuickTile(id, label, state, inputs.dndOn, "Needs access",
                    "$label: $state. Parc needs Do Not Disturb access to switch it. Tap to open the access screen.",
                    QuickAction.AllowDnd)
            }
            "wi-fi" -> panel(id, label, onOff(inputs.wifiOn), inputs.wifiOn, PanelHint, "the Wi-Fi panel", SystemScreen.WifiPanel)
            "bluetooth" -> panel(id, label, onOff(inputs.bluetoothOn), inputs.bluetoothOn, SettingsHint, "Bluetooth settings", SystemScreen.BluetoothSettings)
            "mobile data" -> panel(id, label, "Not shown", null, PanelHint, "the internet panel", SystemScreen.InternetPanel)
            "airplane mode" -> panel(id, label, onOff(inputs.airplaneOn), inputs.airplaneOn, SettingsHint, "airplane mode settings", SystemScreen.AirplaneSettings)
            "auto-rotate" -> panel(id, label, onOff(inputs.autoRotateOn), inputs.autoRotateOn, SettingsHint, "display settings", SystemScreen.DisplaySettings)
            "battery saver" -> QuickTile(id, label, onOff(inputs.batterySaverOn), inputs.batterySaverOn, SettingsHint,
                "$label: ${onOff(inputs.batterySaverOn)}. Opens battery saver settings.", QuickAction.Open(SystemScreen.BatterySaverSettings))
            "screen timeout" -> QuickTile(id, label, timeout(inputs.screenTimeoutMs), null, SettingsHint,
                "$label: ${timeout(inputs.screenTimeoutMs)}. Opens display settings.", QuickAction.Open(SystemScreen.DisplaySettings))
            else -> null
        }
    }

    fun tiles(ids: List<String>, inputs: QuickToggleInputs): List<QuickTile> = ids.mapNotNull { tile(it, inputs) }

    private fun panel(id: String, label: String, state: String, on: Boolean?, hint: String, target: String, screen: SystemScreen) =
        QuickTile(id, label, state, on, hint, "$label: $state. $NotAllowed $target.", QuickAction.Open(screen))

    /** Columns and how many tiles each size shows: Compact is one row of the first four. */
    fun columns(size: HubSize): Int = if (size == HubSize.Compact) 4 else 3
    fun shown(tiles: List<QuickTile>, size: HubSize): List<QuickTile> = if (size == HubSize.Compact) tiles.take(4) else tiles
}
