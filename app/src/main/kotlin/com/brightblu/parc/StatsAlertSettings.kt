package com.brightblu.parc

import com.brightblu.parc.feature.settings.ui.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.stats.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch

@Composable
internal fun StatsAlertSettings(repository: StatsAlertRepository, skin: Skin) {
    val context = LocalContext.current
    val store = remember(context) { StatsAlertUiStore(context) }
    val enabled by store.enabled.collectAsState(initial = false)
    val thresholds by repository.thresholds.collectAsState()
    val scope = rememberCoroutineScope()
    val hubStore = remember(context) { HubConfigStore(context) }
    val config by hubStore.config.collectAsState(initial = HubConfig())
    val undo = rememberEditorUndo()
    EditorScaffold(EditorSpec.Alerts, skin,
        empty = !enabled || thresholds.none { it.enabled },
        undo = if (undo.action != null) ({ undo.run() }) else null) {
        AlertSwitch("Stats alerts", enabled, skin) { on ->
            scope.launch { store.setEnabled(on) }
            if (!on) undo.offer { scope.launch { store.setEnabled(true) } }
        }
        CellLabel("Choose which readings can warn you and at what level.", skin)
        thresholds.forEach { threshold ->
            val metric = threshold.metric
            AlertSwitch(metric.alertLabel(), threshold.enabled, skin) {
                val on = it
                scope.launch { repository.setEnabled(metric, on) }
                if (!on) undo.offer { scope.launch { repository.setEnabled(metric, true) } }
            }
            val unit = if (metric == StatsAlertMetric.BatteryTemperatureHigh) "°C" else "%"
            val values = (metric.validRange.start.toInt()..metric.validRange.endInclusive.toInt()).toList()
            SettingsCard(skin) {
                SettingsChoice("${metric.alertLabel()} threshold", "${threshold.value.toInt()} $unit", values.map { "$it $unit" }, skin) { index ->
                    scope.launch { repository.setValue(metric, values[index].toFloat()) }
                }
            }
        }
        SettingsSection("24 hour history graphs", skin)
        listOf("system", "power").forEach { page ->
            val graphs = if (page == "system") HubCatalog.systemHistoryTiles else HubCatalog.powerHistoryTiles
            graphs.forEach { (tile, _) ->
                AlertSwitch("${page.replaceFirstChar { it.uppercase() }} · $tile", tile !in config.hiddenTiles[page].orEmpty(), skin) { show ->
                    scope.launch { hubStore.update { it.showTile(page, tile, show) } }
                    if (!show) undo.offer { scope.launch { hubStore.update { it.showTile(page, tile, true) } } }
                }
            }
        }
    }
}

@Composable
private fun AlertSwitch(label: String, value: Boolean, skin: Skin, onChange: (Boolean) -> Unit) {
    SettingsCard(skin) {
        SettingRow(label, skin, SettingKind.Toggle, checked = value, divider = false) { onChange(!value) }
    }
}
