package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.ParcPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import com.brightblu.parc.feature.settings.ui.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/**
 * Backup screen (T17, F-15): export the layout JSON to a document the user picks, or restore one.
 *
 * T266 adds the weekly automatic backup: off until the user turns it on and picks a folder with
 * Android's folder picker. The switch and the folder row are the only controls; the last run and a
 * failed run are reported below them in plain words.
 *
 * Colours and sizes come only from [Skin] and [Metrics]. The pickers use Storage Access Framework
 * contracts, so no permission is needed. This composable owns the launchers; the production host
 * lives in `:app`, so today the screen is reachable through the debug review host.
 */
@Composable
fun LayoutBackupScreen(
    controller: BackupController,
    skin: Skin,
    modifier: Modifier = Modifier,
    topPaddingDp: Float = Metrics.keepOutTopDp,
    weekly: WeeklyBackupUiState = WeeklyBackupUiState(),
    onWeeklyEnabled: (Boolean) -> Unit = {},
    onWeeklyFolder: (String) -> Boolean = { true },
) {
    val state by controller.state.collectAsState()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileName.MimeType),
    ) { uri -> uri?.let { controller.exportTo(it.toString()) } }
    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { controller.restoreFrom(it.toString()) } }
    var enableAfterPick by remember { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val kept = onWeeklyFolder(uri.toString())
            if (kept && enableAfterPick) onWeeklyEnabled(true)
        }
        enableAfterPick = false
    }
    LayoutBackupContent(
        state = state,
        skin = skin,
        onExport = { export.launch(state.suggestedFileName) },
        onImport = { restore.launch(BackupFileName.ImportMimeTypes) },
        onConfirmRestore = controller::confirmRestore,
        onCancelRestore = controller::cancelRestore,
        weekly = weekly,
        onToggleWeekly = {
            when {
                weekly.enabled -> onWeeklyEnabled(false)
                !weekly.folderName.isNullOrBlank() -> onWeeklyEnabled(true)
                else -> {
                    enableAfterPick = true
                    folder.launch(null)
                }
            }
        },
        onChooseWeeklyFolder = {
            enableAfterPick = false
            folder.launch(null)
        },
        modifier = modifier,
        topPaddingDp = topPaddingDp,
    )
}

/** Stateless surface; a preview or a host can drive it without the controller. */
@Composable
fun LayoutBackupContent(
    state: BackupUiState,
    skin: Skin,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirmRestore: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    topPaddingDp: Float = Metrics.keepOutTopDp,
    weekly: WeeklyBackupUiState = WeeklyBackupUiState(),
    onToggleWeekly: () -> Unit = {},
    onChooseWeeklyFolder: () -> Unit = {},
) {
    ParcPage(
        modifier.background(color(skin.colors.bg)).padding(top = topPaddingDp.dp),
        safeTop = false,
        blockGapDp = Metrics.rowGapDp,
    ) {
        BasicText("Backup", style = style(skin, skin.colors.textPrimary, skin.typography.title))
        BasicText(
            "The backup is one JSON file with your layout and settings. Export it before a reinstall, then restore it on the new install.",
            style = style(skin, skin.colors.textSecondary, skin.typography.caption),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsSection("Automatic backup", skin)
        SettingsCard(skin) {
            SettingRow(
                "Back up every week",
                skin,
                SettingKind.Toggle,
                description = "While charging and idle, save the same backup into a folder you choose. Keeps the last 4 weekly files (named parc-weekly-date).",
                checked = weekly.enabled,
                divider = true,
                onClick = onToggleWeekly,
            )
            SettingRow(
                "Backup folder",
                skin,
                SettingKind.Action,
                description = weekly.folderName ?: "No folder chosen",
                icon = "▤",
                divider = true,
                onClick = onChooseWeeklyFolder,
            )
            SettingRow(
                "Last automatic backup",
                skin,
                SettingKind.Value,
                value = weekly.lastBackupLabel,
                divider = false,
                onClick = {},
            )
        }
        val failure = weekly.failureLabel
        if (failure != null) {
            BasicText(
                failure,
                style = style(skin, skin.colors.warn, skin.typography.caption),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ActionRow(
            label = "Export backup",
            detail = state.suggestedFileName,
            enabled = !state.busy,
            skin = skin,
            onClick = onExport,
        )
        ActionRow(
            label = "Restore backup",
            detail = "",
            enabled = !state.busy,
            skin = skin,
            onClick = onImport,
        )
        val pending = state.pendingRestore
        if (pending != null) {
            BasicText(
                "Replace your current layout and settings with " + pending.describe() + "?",
                style = style(skin, skin.colors.textPrimary, skin.typography.caption),
            )
            ActionRow("Replace", "", true, skin, onConfirmRestore)
            ActionRow("Cancel", "", true, skin, onCancelRestore)
        }
        val message = state.message
        if (message != null) {
            BasicText(
                message,
                style = style(skin, skin.colors.textSecondary, skin.typography.caption),
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    detail: String,
    enabled: Boolean,
    skin: Skin,
    onClick: () -> Unit,
) {
    SettingsCard(skin) {
        SettingRow(label, skin, SettingKind.Action, description = detail, enabled = enabled,
            destructive = label == "Restore layout", icon = if (label == "Restore layout") "↓" else "↑", divider = false, onClick = onClick)
    }
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(skin: com.brightblu.parc.model.Skin, tint: Long, token: TypeToken): TextStyle = TextStyle(
    fontFamily = skin.textFamily,
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
