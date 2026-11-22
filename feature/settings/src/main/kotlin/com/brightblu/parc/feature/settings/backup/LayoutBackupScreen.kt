package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.textFamily
import com.brightblu.parc.model.ParcPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import com.brightblu.parc.feature.settings.ui.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    hasBackupKey: () -> Boolean = { false },
    showUpdateTokenNote: Boolean = false,
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
    state.passphrasePurpose?.let { purpose ->
        PassphraseDialog(purpose, state.message, skin, controller::submitPassphrase, controller::cancelPassphrase)
    }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose { controller.cancelPassphrase() }
    }
    val warnAboutKey = !state.encrypted && hasBackupKey()
    LayoutBackupContent(
        state = state,
        warnAboutKey = warnAboutKey,
        showUpdateTokenNote = showUpdateTokenNote,
        skin = skin,
        onToggleEncryption = controller::toggleEncryption,
        onEnterPassphrase = controller::enterPassphraseAgain,
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

private const val KeyWarning = "This backup contains your DeepL key and anyone with the file can read it. Set a passphrase to protect it."

private enum class PlainBackupAction { Export, EnableWeekly }

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
    onToggleEncryption: () -> Unit = {},
    onEnterPassphrase: () -> Unit = {},
    warnAboutKey: Boolean = false,
    showUpdateTokenNote: Boolean = false,
) {
    var plainAction by remember { mutableStateOf<PlainBackupAction?>(null) }
    plainAction?.let { action ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { plainAction = null }) {
            Column(Modifier.fillMaxWidth().background(color(skin.colors.bg))
                .padding(Metrics.rowGapDp.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                BasicText(KeyWarning, style = style(skin, skin.colors.textPrimary, skin.typography.menu))
                ActionRow("Set a passphrase", "", true, skin) {
                    plainAction = null
                    onToggleEncryption()
                }
                ActionRow("Continue without a passphrase", "", true, skin) {
                    plainAction = null
                    when (action) {
                        PlainBackupAction.Export -> onExport()
                        PlainBackupAction.EnableWeekly -> onToggleWeekly()
                    }
                }
            }
        }
    }
    ParcPage(
        modifier.background(color(skin.colors.bg)).padding(top = topPaddingDp.dp),
        safeTop = false,
        blockGapDp = Metrics.rowGapDp,
    ) {
        BasicText("Backup", style = style(skin, skin.colors.textPrimary, skin.typography.title))
        BasicText(
            "The backup is one file with your layout and settings. Export it before a reinstall, then restore it on the new install.",
            style = style(skin, skin.colors.textSecondary, skin.typography.caption),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsCard(skin) {
            SettingRow("Encrypt backups", skin, SettingKind.Toggle,
                description = "Protect manual and weekly backups with a passphrase.",
                checked = state.encrypted, enabled = !state.busy, divider = false, onClick = onToggleEncryption)
        }
        if (state.encrypted) {
            BasicText("After uninstalling Parc, you must remember your passphrase. Nobody can recover it. Forget it and the backup cannot be opened.",
                style = style(skin, skin.colors.textSecondary, skin.typography.caption))
            ActionRow("Enter your passphrase again", "For weekly backups on this phone", !state.busy, skin, onEnterPassphrase)
        }
        if (showUpdateTokenNote) {
            BasicText("Backups exclude the GitHub update token. It gives access to private builds and is easy to recreate.",
                style = style(skin, skin.colors.textSecondary, skin.typography.caption))
        }
        SettingsSection("Automatic backup", skin)
        SettingsCard(skin) {
            SettingRow(
                "Back up every week",
                skin,
                SettingKind.Toggle,
                description = (if (warnAboutKey) "Anyone with the weekly file can read your DeepL key. " else "") + "While charging and idle, save the same backup into a folder you choose. Keeps the last 4 weekly files (named parc-weekly-date).",
                descriptionMaxLines = Int.MAX_VALUE,
                checked = weekly.enabled,
                divider = true,
                onClick = { if (warnAboutKey && !weekly.enabled) plainAction = PlainBackupAction.EnableWeekly else onToggleWeekly() },
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
            onClick = { if (warnAboutKey) plainAction = PlainBackupAction.Export else onExport() },
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

/** Password inputs live only in this dialog, never in saved state or diagnostics. */
@Composable
private fun PassphraseDialog(
    purpose: PassphrasePurpose, message: String?, skin: Skin,
    submit: (String, String) -> Unit, cancel: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = cancel) {
        Column(Modifier.fillMaxWidth().background(color(skin.colors.bg))
            .padding(Metrics.rowGapDp.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
            BasicText(if (purpose == PassphrasePurpose.Enable) "Protect your backups" else "Enter your passphrase",
                style = style(skin, skin.colors.textPrimary, skin.typography.title))
            if (purpose == PassphrasePurpose.Enable) BasicText(
                "Use at least 8 characters. Nobody can recover your passphrase. Forget it and the backup cannot be opened. After uninstalling Parc, you must remember it.",
                style = style(skin, skin.colors.textSecondary, skin.typography.caption))
            PassphraseField("Passphrase", first, { first = it }, skin)
            if (purpose == PassphrasePurpose.Enable) PassphraseField("Repeat passphrase", second, { second = it }, skin)
            if (message != null) BasicText(message, style = style(skin, skin.colors.warn, skin.typography.caption))
            ActionRow("Continue", "", true, skin) { submit(first, second) }
            ActionRow("Cancel", "", true, skin, cancel)
        }
    }
}

@Composable
private fun PassphraseField(label: String, value: String, change: (String) -> Unit, skin: Skin) {
    BasicText(label, style = style(skin, skin.colors.textSecondary, skin.typography.caption))
    androidx.compose.foundation.text.BasicTextField(value, change, singleLine = true,
        textStyle = style(skin, skin.colors.textPrimary, skin.typography.menu),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(color(skin.colors.accent)),
        modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.minTouchDp.dp)
            .background(color(skin.colors.surface)).padding(Metrics.rowGapDp.dp)
            .semantics { contentDescription = label })
}
