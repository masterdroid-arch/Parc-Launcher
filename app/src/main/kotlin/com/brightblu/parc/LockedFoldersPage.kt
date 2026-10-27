package com.brightblu.parc

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.lock.UnlockTimeout
import com.brightblu.parc.feature.settings.ui.SettingKind
import com.brightblu.parc.feature.settings.ui.SettingRow
import com.brightblu.parc.feature.settings.ui.SettingsCard
import com.brightblu.parc.feature.settings.ui.SettingsChoice
import com.brightblu.parc.feature.settings.ui.SettingsSection
import com.brightblu.parc.feature.settings.ui.settingsText
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.locked
import kotlinx.coroutines.launch

/** Body of Parc Settings > Locked folders (T98): the honest explanation, the timeout, the name switch and the folder list. */
@Composable
internal fun LockedFoldersContent(services: LauncherServices, skin: Skin, gate: FolderLockGate) {
    val context = LocalContext.current
    val prefs = remember(context) { ParcUiPrefs(context) }
    val scope = rememberCoroutineScope()
    var timeout by remember { mutableStateOf(prefs.lockTimeout()) }
    var hideName by remember { mutableStateOf(prefs.lockHideName()) }
    val layout by services.layout.layout.collectAsState()
    val unlocked by services.folderLock.windows.collectAsState()

    BasicText(com.brightblu.parc.core.data.lock.LockedFolderText.Honesty,
        style = settingsText(skin, skin.colors.textSecondary, true),
        modifier = Modifier.padding(vertical = Metrics.gridDp.dp * 2))
    SettingsSection("Unlocking", skin)
    SettingsCard(skin) {
        SettingsChoice("Unlock lasts", timeout.label, UnlockTimeout.entries.map { it.label }, skin) { index ->
            timeout = UnlockTimeout.entries[index]
            prefs.setLockTimeout(timeout)
        }
        SettingRow("Hide folder names", skin, SettingKind.Toggle,
            "Show Locked instead of the name while a folder is locked", checked = hideName) {
            hideName = !hideName
            prefs.setLockHideName(hideName)
        }
        SettingRow("Unlock all", skin, SettingKind.Action, "Authenticate to show every locked folder temporarily") {
            gate.authenticate(openWindow = true) {}
        }
        if (unlocked.isNotEmpty()) SettingRow("Lock now", skin, SettingKind.Action, "Hide the apps in locked folders again", icon = "○") {
            gate.relock()
        }
    }
    BasicText("The unlock also ends when you leave Parc or the screen turns off.",
        style = settingsText(skin, skin.colors.textSecondary, true),
        modifier = Modifier.padding(top = Metrics.gridDp.dp * 2))
    SettingsSection("Folders", skin)
    if (layout.folders.isEmpty()) {
        BasicText("No folders yet. Create one under Folders first.",
            style = settingsText(skin, skin.colors.textSecondary, true))
    } else SettingsCard(skin) {
        layout.folders.sortedWith(compareByDescending<com.brightblu.parc.model.Folder> { it.locked }.thenBy { it.name.lowercase() })
            .forEach { folder ->
                val locked = folder.locked
                SettingRow(if (hideName && gate.isFolderLockedNow(folder.id)) "Locked" else folder.name, skin, SettingKind.Action,
                    if (locked) "Locked. Tap to remove the lock" else "Not locked. Tap to lock this folder",
                    icon = if (locked) "✓" else "○") {
                    if (locked) {
                        if (gate.isFolderLockedNow(folder.id)) {
                            gate.authenticate(openWindow = false) {
                                scope.launch { services.layout.setFolderLockFlag(folder.id, false) }
                            }
                        } else scope.launch { services.layout.setFolderLockFlag(folder.id, false) }
                    } else {
                        gate.authenticate(openWindow = false) {
                            scope.launch {
                                services.layout.setFolderLockFlag(folder.id, true)
                                gate.relock()
                            }
                        }
                    }
                }
            }
    }
}
