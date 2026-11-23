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
import com.brightblu.parc.model.parcIgnoreObscuredTouches
import androidx.compose.foundation.layout.Box

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
    val weeklyNow by androidx.compose.runtime.rememberUpdatedState(weekly)
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
            // T402: the weekly file is always encrypted, so turning it on asks for a passphrase first.
            // Cancelling that set-up leaves the switch off.
            if (weeklyNow.enabled) onWeeklyEnabled(false)
            else controller.requirePassphrase {
                if (!weeklyNow.folderName.isNullOrBlank()) onWeeklyEnabled(true)
                else {
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

/**
 * T373: starts an export from anywhere (the Data & help group) with exactly the Backup page's steps: the plain-key
 * warning, the passphrase dialog, Android's file picker and [BackupController.exportTo]. [content] draws the button;
 * cancelling the picker changes nothing, so the button stays where it was.
 */
@Composable
fun BackupExportHost(
    controller: BackupController,
    skin: Skin,
    hasBackupKey: () -> Boolean,
    content: @Composable (start: () -> Unit, busy: Boolean, message: String?, status: List<String>) -> Unit,
) {
    val state by controller.state.collectAsState()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileName.MimeType),
    ) { uri -> uri?.let { controller.exportTo(it.toString()) } }
    var warn by remember { mutableStateOf(false) }
    state.passphrasePurpose?.let { purpose ->
        PassphraseDialog(purpose, state.message, skin, controller::submitPassphrase, controller::cancelPassphrase)
    }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose { controller.cancelPassphrase() }
    }
    if (warn) androidx.compose.material3.AlertDialog(
        onDismissRequest = { warn = false },
        containerColor = color(skin.colors.surface),
        title = { BasicText("Back up without a passphrase?", style = style(skin, skin.colors.textPrimary, skin.typography.menu)) },
        text = { BasicText(KeyLeftOut, style = style(skin, skin.colors.textPrimary, skin.typography.menu)) },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { warn = false; controller.toggleEncryption() }) {
                BasicText("Set a passphrase", style = style(skin, skin.colors.accent, skin.typography.menu))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { warn = false; export.launch(state.suggestedFileName) }) {
                BasicText("Continue without the key", style = style(skin, skin.colors.textPrimary, skin.typography.menu))
            }
        },
    )
    content({ if (!state.encrypted && hasBackupKey()) warn = true else export.launch(state.suggestedFileName) },
        state.busy, state.message, BackupExportCopy.lines(state.encrypted, hasBackupKey()))
}

private const val KeyLeftOut = BackupExportCopy.KeyLeftOut

/**
 * T402: the plain words about what an export contains. [Encrypted] and [NotEncrypted] are the one clear
 * line on the export screen; a plain-text file never carries the DeepL key, so when one is saved on this
 * phone and backups are not encrypted the screen says it is left out.
 */
object BackupExportCopy {
    const val Encrypted = "Encrypted"
    const val NotEncrypted = "Not encrypted"
    const val KeyLeftOut = "This backup is not encrypted, so your DeepL key is left out. Set a passphrase to include it."

    fun lines(encrypted: Boolean, hasKey: Boolean): List<String> =
        if (encrypted) listOf(Encrypted)
        else if (hasKey) listOf(NotEncrypted, KeyLeftOut)
        else listOf(NotEncrypted)
}

/**
 * T379: the restore confirmation names both sides of the replacement and keeps Cancel beside the
 * destructive action, whatever the page's scroll position.
 */
internal object RestoreCopy {
    const val Title = "Restore this backup?"
    fun consequence(summary: String) = "This replaces your current layout and settings with $summary."
    const val Confirm = "Restore"
    const val Cancel = "Cancel"
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
    onToggleEncryption: () -> Unit = {},
    onEnterPassphrase: () -> Unit = {},
    warnAboutKey: Boolean = false,
    showUpdateTokenNote: Boolean = false,
) {
    var confirmPlainExport by remember { mutableStateOf(false) }
    if (confirmPlainExport) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmPlainExport = false }) {
            Column(Modifier.fillMaxWidth().background(color(skin.colors.bg))
                .padding(Metrics.rowGapDp.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.rowGapDp.dp)) {
                BasicText(KeyLeftOut, style = style(skin, skin.colors.textPrimary, skin.typography.menu))
                ActionRow("Set a passphrase", "", true, skin) {
                    confirmPlainExport = false
                    onToggleEncryption()
                }
                ActionRow("Continue without the key", "", true, skin) {
                    confirmPlainExport = false
                    onExport()
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
                description = "Saves weekly while charging and idle. Always encrypted.",
                info = "Adds the same encrypted backup to a folder you choose and keeps the last 4 weekly files. Turning it on asks for a passphrase first.",
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
        val failure = WeeklyBackupUiState.statusLine(weekly, state.encrypted)
        if (failure != null) {
            BasicText(
                failure,
                style = style(skin, skin.colors.warn, skin.typography.caption),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ActionRow(
            label = "Export backup",
            detail = (listOf(state.suggestedFileName) + BackupExportCopy.lines(state.encrypted, warnAboutKey)).joinToString("\n"),
            enabled = !state.busy,
            skin = skin,
            onClick = { if (warnAboutKey) confirmPlainExport = true else onExport() },
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
            SettingsConfirmation(
                RestoreCopy.Title,
                RestoreCopy.consequence(pending.describe()),
                RestoreCopy.Confirm,
                skin,
                onCancel = onCancelRestore,
                onConfirm = onConfirmRestore,
                protectConfirm = true,
            )
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
            // T403: Continue confirms the passphrase, so it ignores touches while another app's window covers it.
            Box(Modifier.parcIgnoreObscuredTouches()) { ActionRow("Continue", "", true, skin) { submit(first, second) } }
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
