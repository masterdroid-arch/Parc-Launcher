package com.brightblu.parc

import com.brightblu.parc.model.textFamily
import android.app.NotificationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LocalFocusSentence = staticCompositionLocalOf<(FocusMode) -> String> { { focusModeSentence(it) } }
internal val LocalEditFocus = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun FocusHubPage(skin: Skin) {
    val repository = LocalFocusRepository.current ?: return
    val edit = LocalEditFocus.current
    val sentence = LocalFocusSentence.current
    val state by repository.state.collectAsState()
    val modes by repository.modes.collectAsState()
    val summary by repository.summary.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(Metrics.focusRefreshMs) } }
    val model = focusHubModel(state, modes, maxOf(now, state.evaluatedAtMillis))
    val scope = rememberCoroutineScope()
    var issue by remember { mutableStateOf("") }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { issue = ""; block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue = "Could not update Focus. Try again." }
    } }
    var starting by remember { mutableStateOf<String?>(null) }
    var exit by remember { mutableStateOf<FocusExit?>(null) }
    exit?.let { FocusExitDialog(repository, it, skin, onDismiss = { exit = null }) }
    val context = LocalContext.current
    val hubStore = remember(context) { HubConfigStore(context) }
    val hubConfig by hubStore.config.collectAsState(initial = HubConfig())
    val dndAllowed = runCatching { context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)
    starting?.let { name ->
        AlertDialog(onDismissRequest = { starting = null }, containerColor = Color(skin.colors.surface.toInt()),
            title = { SettingsSection("Start $name", skin) },
            text = { Column { listOf("30 min" to 30, "1 h" to 60, "2 h" to 120, "Until tomorrow" to null).forEach { (label, minutes) ->
                SettingRow(label, skin, SettingKind.Action) { action { repository.quickStart(name, minutes); starting = null } }
            } } }, confirmButton = { TextButton(onClick = { starting = null }) { SettingsSection("Cancel", skin) } })
    }
    Column(Modifier.fillMaxSize().hubPageScroll(), verticalArrangement = Arrangement.spacedBy(Metrics.hudCellGapDp.dp)) {
        HudCell(skin, Modifier.fillMaxWidth()) { Column {
            val active = state.active
            HubHero(if (active != null) focusRemaining(state.copy(evaluatedAtMillis = maxOf(now, state.evaluatedAtMillis)))
                else if (state.breakUntilMillis != null) "Paused" else "Ready", active?.name ?: model.status, skin)
            active?.accent?.let { accent ->
                androidx.compose.foundation.text.BasicText("●", style = androidx.compose.ui.text.TextStyle(fontFamily = skin.textFamily, 
                    color = Color(accent.toInt()), fontSize = skin.typography.menu.sizeSp.sp))
            }
            if (LocalHubSize.current != HubSize.Compact) active?.let { androidx.compose.foundation.text.BasicText(sentence(it), style = hudValueStyle(skin)) }
            if (active != null) {
                if (LocalHubSize.current != HubSize.Compact) androidx.compose.foundation.text.BasicText(FocusFrictionRules.status(active, now, java.time.ZoneId.systemDefault()), style = hudLabelStyle(skin))
                Row {
                    Box(Modifier.weight(1f)) { SettingRow("Pause", skin, SettingKind.Action) { exit = FocusExit.Pass } }
                    Box(Modifier.weight(1f)) { SettingRow("End", skin, SettingKind.Action) { exit = FocusExit.End } }
                }
                SettingRow("Edit mode", skin, SettingKind.Page, onClick = edit)
            }
        } }
        if (issue.isNotEmpty()) SettingsSection(issue, skin)
        model.nextSchedule?.let { SettingsSection(it, skin) }
        summary?.let {
            SettingsCard(skin) {
                SettingsSection("Focus summary", skin)
                SettingsSection(focusSummaryText(it), skin)
                SettingRow("Dismiss summary", skin, SettingKind.Action) { repository.consumeSummary() }
            }
        }
        if (LocalHubSize.current != HubSize.Compact) SettingsSection("All modes", skin)
        if (modes.isEmpty()) SettingsSection("Create a mode in Focus settings", skin)
        modes.take(if (LocalHubSize.current == HubSize.Compact) 1 else if (LocalHubSize.current == HubSize.Standard) 3 else modes.size).forEach { mode ->
            SettingsCard(skin) {
                SettingRow("Start ${mode.name}", skin, SettingKind.Action,
                    enabled = mode.enabled && state.active?.strict != true && state.breakUntilMillis == null) { starting = mode.name }
            }
        }
        SettingRow("Edit Focus modes", skin, SettingKind.Page, description = "Choose apps, times and how strict to be") { edit() }
    }
}
