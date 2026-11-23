package com.brightblu.parc

import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T219/T220: Home > Hidden & locked. The three registry rows carry their own consequence and Info target
 * (T379), so there is no second Visibility explainer card above them.
 */
@Composable
internal fun HiddenLockedPage(
    skin: Skin,
    snapshot: SettingsSnapshot,
    target: String?,
    onValue: (SettingDef, Int) -> Unit,
    onOpen: (SettingDef) -> Unit,
    custom: @Composable (SettingDef) -> Unit,
) {
    SettingsContent(skin, SettingsPage.HiddenLocked.title) {
        RegistryRows(SettingsPage.HiddenLocked, snapshot, skin, target, onValue, onOpen, custom)
    }
}
