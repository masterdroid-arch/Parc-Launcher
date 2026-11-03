package com.brightblu.parc

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.FeelEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val Tag = "QuickToggles"
private const val ReadIntervalMs = 2_000L
private const val TileMaxHeightDp = 112f
private const val TallNoteRoomDp = 56f

/** Logs each kind of failure once per process, so a polling read cannot flood the log. */
private val loggedOnce = ConcurrentHashMap.newKeySet<String>()
private fun logOnce(key: String, error: Throwable) {
    if (loggedOnce.add(key)) ParcLog.w(Tag, "$key failed", error)
}

/** T136: the torch through CameraManager. No permission is needed for the torch. Never throws. */
internal class CameraTorch(context: Context) : TorchHardware {
    private val manager = context.getSystemService(CameraManager::class.java)
    @Volatile private var cachedId: String? = null
    @Volatile private var looked = false

    override fun flashCameraId(): String? {
        if (looked) return cachedId
        val manager = manager ?: return null
        val id = try {
            manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
                .filter { (_, c) -> c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                .sortedBy { (_, c) -> if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 }
                .firstOrNull()?.first
        } catch (e: CameraAccessException) {
            logOnce("camera list", e); return null
        } catch (e: RuntimeException) {
            logOnce("camera list", e); return null
        }
        cachedId = id
        looked = true
        return id
    }

    override fun setTorch(cameraId: String, on: Boolean): TorchResult {
        val manager = manager ?: return TorchResult.Missing
        return try {
            manager.setTorchMode(cameraId, on)
            TorchResult.Done
        } catch (e: CameraAccessException) {
            ParcLog.w(Tag, "torch refused (reason ${e.reason})", e)
            TorchResult.Busy
        } catch (e: IllegalArgumentException) {
            ParcLog.w(Tag, "torch not available on this camera", e)
            TorchResult.Missing
        } catch (e: RuntimeException) {
            ParcLog.w(Tag, "torch failed", e)
            TorchResult.Busy
        }
    }
}

/** Reads what Android shows without a permission; each failed reading becomes "Not shown". */
internal object QuickToggleSystem {
    fun read(context: Context): QuickToggleInputs {
        val resolver = context.contentResolver
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun <T> reading(name: String, block: () -> T): T? = try { block() } catch (e: RuntimeException) { logOnce(name, e); null }
        return QuickToggleInputs(
            dndAccess = reading("dnd access") { notifications?.isNotificationPolicyAccessGranted } == true,
            dndOn = reading("dnd state") {
                notifications?.currentInterruptionFilter?.let {
                    it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                }
            },
            wifiOn = reading("wifi state") { context.getSystemService(WifiManager::class.java)?.isWifiEnabled },
            bluetoothOn = reading("bluetooth state") {
                Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, -1).takeIf { it >= 0 }?.let { it != 0 }
            },
            airplaneOn = reading("airplane state") {
                Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, -1).takeIf { it >= 0 }?.let { it != 0 }
            },
            autoRotateOn = reading("rotation state") {
                Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, -1).takeIf { it >= 0 }?.let { it != 0 }
            },
            batterySaverOn = reading("battery saver state") { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode },
            screenTimeoutMs = reading("screen timeout") {
                Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L).takeIf { it > 0 }
            },
        )
    }

    /** Switches Do Not Disturb with the access Focus already uses. False when Android refused. */
    fun setDnd(context: Context, on: Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            if (!manager.isNotificationPolicyAccessGranted) return false
            manager.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        } catch (e: RuntimeException) {
            ParcLog.w(Tag, "Do Not Disturb change refused", e)
            false
        }
    }

    fun intent(screen: SystemScreen): Intent = Intent(when (screen) {
        SystemScreen.WifiPanel -> Settings.Panel.ACTION_WIFI
        SystemScreen.BluetoothSettings -> Settings.ACTION_BLUETOOTH_SETTINGS
        SystemScreen.InternetPanel -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        SystemScreen.AirplaneSettings -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        SystemScreen.DisplaySettings -> Settings.ACTION_DISPLAY_SETTINGS
        SystemScreen.BatterySaverSettings -> Settings.ACTION_BATTERY_SAVER_SETTINGS
    })

    /** Opens the screen, falling back to the main Settings screen. False when neither opened. */
    fun open(context: Context, intent: Intent): Boolean {
        for (candidate in listOf(intent, Intent(Settings.ACTION_SETTINGS))) {
            try {
                context.startActivity(candidate)
                return true
            } catch (e: RuntimeException) {
                ParcLog.w(Tag, "could not open ${candidate.action}", e)
            }
        }
        return false
    }
}

/**
 * T136: the Quick toggles page. In demo mode it shows fixed sample states and taps change nothing,
 * so screenshots never show or change the device's real state.
 */
@Composable
internal fun QuickTogglesHubPage(tiles: List<String>, active: Boolean, skin: Skin, demo: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val feel = rememberFeelPlayer()
    val scope = rememberCoroutineScope()
    val size = LocalHubSize.current
    var readings by remember { mutableStateOf(QuickToggleInputs()) }
    var torch by remember { mutableStateOf(TorchState.Off) }
    var failure by remember { mutableStateOf<String?>(null) }
    val hardware = remember(context) { CameraTorch(context.applicationContext) }
    if (!demo) {
        LaunchedEffect(active) {
            while (active) {
                readings = withContext(Dispatchers.IO) { QuickToggleSystem.read(context.applicationContext) }
                delay(ReadIntervalMs)
            }
        }
        DisposableEffect(hardware, active) {
            val manager = context.getSystemService(CameraManager::class.java)
            var ourId: String? = null
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torch = TorchLogic.onCallback(torch, ourId, cameraId, enabled)
                }
                override fun onTorchModeUnavailable(cameraId: String) {
                    torch = TorchLogic.onCallback(torch, ourId, cameraId, null)
                }
            }
            val job = if (active && manager != null) scope.launch {
                ourId = withContext(Dispatchers.IO) { hardware.flashCameraId() }
                if (ourId == null) torch = TorchState.Missing
                else try {
                    manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                } catch (e: RuntimeException) {
                    logOnce("torch callback", e)
                }
            } else null
            onDispose {
                job?.cancel()
                try { manager?.unregisterTorchCallback(callback) } catch (e: RuntimeException) { logOnce("torch callback removal", e) }
            }
        }
    }
    val inputs = if (demo) QuickToggleInputs.Demo else readings.copy(torch = torch)
    val shown = QuickToggleTiles.shown(QuickToggleTiles.tiles(tiles, inputs), size)
    if (shown.isEmpty()) {
        CellLabel("No tiles shown · use …", skin)
        return
    }
    fun run(tile: QuickTile) {
        feel.play(view, FeelEvent.Tick)
        failure = null
        if (demo) return
        when (val action = tile.action) {
            QuickAction.ToggleTorch -> scope.launch {
                torch = withContext(Dispatchers.IO) { TorchLogic.toggle(hardware, torch) }
            }
            QuickAction.ToggleDnd -> {
                if (QuickToggleSystem.setDnd(context, readings.dndOn != true)) readings = readings.copy(dndOn = readings.dndOn != true)
                else failure = "Android did not change Do Not Disturb"
            }
            QuickAction.AllowDnd -> if (!QuickToggleSystem.open(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
                failure = "Could not open the access screen"
            is QuickAction.Open -> if (!QuickToggleSystem.open(context, QuickToggleSystem.intent(action.screen)))
                failure = "Could not open that screen"
        }
    }
    val columns = QuickToggleTiles.columns(size)
    val rows = (shown.size + columns - 1) / columns
    val gap = Metrics.hudCellGapDp
    val room = LocalHubContentHeight.current - (if (size == HubSize.Tall) TallNoteRoomDp else 0f)
    val tileHeight = ((room - gap * (rows - 1)) / rows).coerceIn(Metrics.touchTargetDp, TileMaxHeightDp)
    Column(Modifier.fillMaxSize().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        shown.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                row.forEach { tile ->
                    QuickTileCell(tile, skin, size, Modifier.weight(1f).fillMaxHeight().heightIn(min = tileHeight.dp)) { run(tile) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        failure?.let { BasicText(it, style = hudValueStyle(skin, skin.colors.warn)) }
        if (size == HubSize.Tall) BasicText(QuickToggleCatalog.PlatformNote, style = hudLabelStyle(skin))
    }
}

@Composable
private fun QuickTileCell(tile: QuickTile, skin: Skin, size: HubSize, modifier: Modifier, onTap: () -> Unit) {
    // An "on" tile borrows the accent for its border, like warning tiles on the System page.
    val tileSkin = remember(skin, tile.on) {
        if (tile.on == true) skin.copy(colors = skin.colors.copy(lineBase = skin.colors.accent)) else skin
    }
    HudCell(tileSkin, modifier
        .semantics(mergeDescendants = true) { contentDescription = tile.description; role = Role.Button }
        .clickable(onClick = onTap), narrow = true) {
        Column {
            BasicText(tile.label, style = hudLabelStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(tile.state, style = hudValueStyle(skin, if (tile.on == true) skin.colors.accent else null),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (size != HubSize.Compact) BasicText(tile.hint, style = hudLabelStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
