package com.brightblu.parc

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.hidden.VisibilityCard
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/**
 * T219/T220: Home > Hidden & locked. One Visibility explanation, then the three registry rows:
 * permanent hidden apps, hidden folders and locked folders. Their ids and destinations do not change.
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
        VisibilityCard(skin)
        Spacer(Modifier.height(Metrics.sectionHeaderGapDp.dp))
        RegistryRows(SettingsPage.HiddenLocked, snapshot, skin, target, onValue, onOpen, custom)
    }
}
