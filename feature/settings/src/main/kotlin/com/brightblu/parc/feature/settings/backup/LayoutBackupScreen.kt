package com.brightblu.parc.feature.settings.backup

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
 * Colours and sizes come only from [Skin] and [Metrics]. The pickers use Storage Access Framework
 * contracts, so no permission is needed. This composable owns the launchers; the production host
 * lives in `:app`, so today the screen is reachable through the debug review host.
 */
@Composable
fun LayoutBackupScreen(controller: BackupController, skin: Skin, modifier: Modifier = Modifier, topPaddingDp: Float = Metrics.keepOutTopDp) {
    val state by controller.state.collectAsState()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileName.MimeType),
    ) { uri -> uri?.let { controller.exportTo(it.toString()) } }
    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { controller.restoreFrom(it.toString()) } }
    LayoutBackupContent(
        state = state,
        skin = skin,
        onExport = { export.launch(state.suggestedFileName) },
        onImport = { restore.launch(BackupFileName.ImportMimeTypes) },
        onConfirmRestore = controller::confirmRestore,
        onCancelRestore = controller::cancelRestore,
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
) {
    Column(
        modifier
            .fillMaxSize()
            .background(color(skin.colors.bg))
            .verticalScroll(rememberScrollState())
            .padding(
                start = Metrics.textGutterDp.dp,
                end = Metrics.textGutterDp.dp,
                top = topPaddingDp.dp,
                bottom = Metrics.keepOutBottomDp.dp,
            ),
    ) {
        BasicText("Backup", style = style(skin.colors.textPrimary, skin.typography.title))
        BasicText(
            "The backup is one JSON file with your layout and settings. Export it before a reinstall, then restore it on the new install.",
            style = style(skin.colors.textSecondary, skin.typography.caption),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Metrics.gridDp.dp * 2, bottom = Metrics.gridDp.dp * 2),
        )
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
                style = style(skin.colors.textPrimary, skin.typography.caption),
                modifier = Modifier.padding(top = Metrics.gridDp.dp * 2),
            )
            ActionRow("Replace", "", true, skin, onConfirmRestore)
            ActionRow("Cancel", "", true, skin, onCancelRestore)
        }
        val message = state.message
        if (message != null) {
            BasicText(
                message,
                style = style(skin.colors.textSecondary, skin.typography.caption),
                modifier = Modifier.padding(vertical = Metrics.gridDp.dp * 2),
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
            destructive = label == "Restore layout", icon = if (label == "Restore layout") "↓" else "↑", onClick = onClick)
    }
}

private fun color(argb: Long): Color = Color(argb.toInt())

private fun style(tint: Long, token: TypeToken): TextStyle = TextStyle(
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)
