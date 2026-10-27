package com.brightblu.parc

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives only framework pin requests. Invalid intents cannot inject a shortcut. */
class PinItemActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = try { getSystemService(LauncherApps::class.java).getPinItemRequest(intent) } catch (_: RuntimeException) { null }
        val info = platform?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        val shortcut = info?.let { Slot.Shortcut(it.`package`, it.id,
            getSystemService(UserManager::class.java).getSerialNumberForUser(it.userHandle),
            it.shortLabel?.toString()?.take(120).orEmpty().ifBlank { "Shortcut" }) }
        val request = object : PinRequest {
            override val valid get() = platform?.isValid == true
            override val enabled get() = info?.isEnabled == true
            override val shortcut = shortcut
            override fun accept() = platform?.accept() == true
        }
        val controller = PinController(request)
        setContent {
            val metrics = resources.displayMetrics
            val geometry = remember { RingLayout(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density) }
            val services by produceState<LauncherServices?>(null) { value = AppGraph.get(applicationContext, geometry) }
            val skin = services?.skinProvider?.active?.collectAsState()?.value ?: Skin.Midnight
            val scope = rememberCoroutineScope()
            var destination by remember { mutableStateOf<FolderId?>(null) }
            var busy by remember { mutableStateOf(false) }
            val live by (services?.liveReady ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            val layout by (services?.layout?.layout ?: kotlinx.coroutines.flow.MutableStateFlow(ParcLayout())).collectAsState()
            Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                SettingsTheme(skin) {
                    AlertDialog(onDismissRequest = { controller.decline(); finish() },
                        containerColor = Color(skin.colors.surface.toInt()),
                        title = { BasicText(if (shortcut != null) "Add ${shortcut.label} to Parc?" else if (platform?.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET)
                            "Widgets are not supported" else "Shortcut unavailable", style = settingsText(skin)) },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (shortcut != null && request.valid && request.enabled) {
                                BasicText("Choose where to add this shortcut", style = settingsText(skin, skin.colors.textSecondary))
                                SettingRow("First free slot", skin, SettingKind.Action, icon = if (destination == null) "✓" else "○") { destination = null }
                                layout.folders.filterNot { it.locked }.forEach { folder ->
                                    SettingRow(folder.name, skin, SettingKind.Action, icon = if (destination == folder.id) "✓" else "○") { destination = folder.id }
                                }
                            } else BasicText(if (shortcut == null && platform?.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET)
                                "Parc supports apps and shortcuts. This widget was not added." else "This request has expired or the shortcut is disabled.", style = settingsText(skin))
                        } },
                        confirmButton = { if (shortcut != null && request.valid && request.enabled) TextButton(enabled = live && !busy, onClick = {
                            busy = true
                            scope.launch {
                                val host = services ?: return@launch
                                val counts = ParcUiPrefs(this@PinItemActivity).layoutCounts()
                                val shown = RingLayout(geometry.widthDp, geometry.heightDp, options = RingLayoutOptions(arcsShown = counts.arcs, iconsPerArc = counts.icons))
                                var result = PinResult.Unavailable
                                try {
                                    withContext(Dispatchers.IO) {
                                        host.layout.updateLayout { current ->
                                            var next = current
                                            result = controller.confirm(LayoutCounts.migrate(current, counts.arcs), shown.arcs, destination) { next = it }
                                            next
                                        }
                                    }
                                    Toast.makeText(this@PinItemActivity, when (result) {
                                        PinResult.Added -> "Shortcut added"
                                        PinResult.Duplicate -> "Shortcut is already in Parc"
                                        else -> "Shortcut unavailable"
                                    }, Toast.LENGTH_SHORT).show()
                                } finally { finish() }
                            }
                        }) { BasicText("Add", style = settingsText(skin, skin.colors.accent)) } },
                        dismissButton = { TextButton(onClick = { controller.decline(); finish() }) {
                            BasicText(if (shortcut == null) "Close" else "Cancel", style = settingsText(skin, skin.colors.accent))
                        } })
                }
            }
        }
        configureParcWindow()
    }
}
