package com.brightblu.parc.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.parcIgnoreObscuredTouches
import com.brightblu.parc.model.parcMinTouch
import com.brightblu.parc.model.readableAccent

/** Each control owns its gesture and accessibility node. The label can grow to several lines. */
@Composable
fun SettingsTwoTargetRow(label: String, helper: String, skin: Skin, checked: Boolean,
    onToggle: (Boolean) -> Unit, secondary: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Metrics.settingsHelperRowDp.dp)
        .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.lineGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = settingsText(skin))
            BasicText(helper, style = settingsText(skin, skin.colors.textSecondary, true))
        }
        secondary()
        SettingsSwitch(checked, skin, label, onToggle)
    }
}

@Composable
fun SettingsInfo(label: String, explanation: String, skin: Skin) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Box(Modifier.clickable(role = Role.Button) { open = true }.parcMinTouch()
        .semantics { contentDescription = "Info about $label" }, contentAlignment = Alignment.Center) {
        BasicText("ⓘ", style = settingsText(skin, skin.colors.textSecondary)
            .copy(fontSize = skin.typography.section.sizeSp.sp))
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(label, style = settingsText(skin)) },
        text = { BasicText(explanation, style = settingsText(skin)) },
        confirmButton = { TextButton(onClick = { open = false }) { BasicText("Done", style = settingsText(skin)) } })
}

@Composable
fun SettingsAccessHint(label: String, helper: String, skin: Skin, onRequest: () -> Unit) {
    SettingRow(label, skin, SettingKind.Action, description = helper, icon = "↗", onClick = onRequest)
}

@Composable
fun SettingsEmptyState(title: String, skin: Skin, helper: String = "", action: (@Composable () -> Unit)? = null) {
    SettingsPanel(skin) {
        BasicText(title, style = settingsText(skin))
        if (helper.isNotBlank()) BasicText(helper, style = settingsText(skin, skin.colors.textSecondary))
        action?.invoke()
    }
}

@Composable
fun SettingsConfirmation(title: String, consequence: String, action: String, skin: Skin,
    onCancel: () -> Unit, protectConfirm: Boolean = false, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, containerColor = Color(skin.colors.surface.toInt()),
        title = { BasicText(title, style = settingsText(skin)) },
        text = { BasicText(consequence, style = settingsText(skin)) },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.parcMinTouch()) {
            BasicText("Cancel", style = settingsText(skin))
        } },
        // T403: a protected confirm (backup restore) ignores touches while another app's window covers it.
        confirmButton = { TextButton(onClick = onConfirm,
            modifier = (if (protectConfirm) Modifier.parcIgnoreObscuredTouches() else Modifier).parcMinTouch()) {
            BasicText(action, style = settingsText(skin, skin.colors.danger))
        } })
}

@Composable
fun SettingsStepper(label: String, value: String, skin: Skin, canDecrease: Boolean, canIncrease: Boolean,
    onDecrease: () -> Unit, onIncrease: () -> Unit, description: String = "", info: String = "") {
    // T372: at enlarged text the value moves under the label instead of squeezing the label beside
    // two 48 dp buttons.
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    // T374: the explanation sits under the whole row, so it is not squeezed into the width left beside two buttons.
    Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.settingsRowInsetDp.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Metrics.settingsRowMinHeightDp.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                    BasicText(label, Modifier.weight(1f), style = settingsText(skin))
                    if (info.isNotBlank()) SettingsInfo(label, info, skin)
                }
                if (stacked) BasicText(value, style = settingsText(skin, skin.colors.readableAccent),
                    modifier = Modifier.padding(top = Metrics.lineGapDp.dp))
            }
            StepButton("−", "Decrease $label", canDecrease, skin, onDecrease)
            if (!stacked) BasicText(value, Modifier.widthIn(min = Metrics.menuRowHeightDp.dp)
                .semantics { contentDescription = "$label, $value" },
                style = settingsText(skin, skin.colors.readableAccent).copy(textAlign = TextAlign.Center), maxLines = 2)
            StepButton("+", "Increase $label", canIncrease, skin, onIncrease)
        }
        if (description.isNotBlank()) BasicText(description, style = settingsText(skin, skin.colors.textSecondary, true),
            modifier = Modifier.padding(bottom = Metrics.rowGapDp.dp))
    }
}

@Composable
private fun StepButton(mark: String, label: String, enabled: Boolean, skin: Skin, onClick: () -> Unit) {
    Box(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).parcMinTouch()
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        BasicText(mark, style = settingsText(skin, if (enabled) skin.colors.textPrimary else skin.colors.textSecondary))
    }
}
