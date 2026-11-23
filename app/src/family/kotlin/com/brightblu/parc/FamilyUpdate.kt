package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import com.brightblu.parc.familyupdate.UpdateRuntime
import com.brightblu.parc.familyupdate.UpdateScheduler
import com.brightblu.parc.familyupdate.UpdateSettingsRows
import com.brightblu.parc.familyupdate.UpdateHubRow
import com.brightblu.parc.familyupdate.UpdateRootRow
import com.brightblu.parc.model.Skin

/**
 * T396: the family build's update checker, as the rest of the app sees it. Compiled only into the family flavour
 * (`src/family`); personal, personalWeather and play compile the empty twin in `src/noFamilyUpdate`.
 */
internal object FamilyUpdate {
    const val Available = true

    /** The one Settings row (Data & help > This build > Updates). Its search words are update, upgrade and version. */
    val settingRows: List<SettingDef> = listOf(
        SettingDef("UpdateCheck", "Check for updates", "Asks GitHub once a day.",
            SettingsPage.DataBuild, "Updates", SettingControl.Custom, SettingEffect.None,
            listOf("update", "upgrade", "version", "new version", "install", "download", "release", "check now", "last checked")),
    )

    /** From process start: keeps the once-a-day background job in step with the switch. */
    fun onAppStart(context: Context) {
        val engine = UpdateRuntime.engine(context)
        UpdateScheduler.sync(context, engine.ui.value.autoCheck)
        engine.checkInBackgroundIfDue()
    }

    @Composable fun HubRow(skin: Skin) = UpdateHubRow(skin)
    @Composable fun RootRow(skin: Skin, open: () -> Unit) = UpdateRootRow(skin, open)
    @Composable fun SettingsRows(skin: Skin) = UpdateSettingsRows(skin)
}
