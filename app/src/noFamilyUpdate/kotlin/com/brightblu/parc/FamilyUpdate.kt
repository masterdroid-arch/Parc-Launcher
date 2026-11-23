package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T396: the empty twin of the family update checker, compiled by personal, personalWeather and play (and the public
 * snapshot's non-family builds). They have no public update check: no code, no row, no permission.
 */
internal object FamilyUpdate {
    const val Available = false
    val settingRows: List<SettingDef> = emptyList()
    fun onAppStart(context: Context) = Unit
    @Composable fun HubRow(skin: Skin) = Unit
    @Composable fun RootRow(skin: Skin, open: () -> Unit) = Unit
    @Composable fun SettingsRows(skin: Skin) = Unit
}
