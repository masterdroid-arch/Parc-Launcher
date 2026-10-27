package com.brightblu.parc.feature.settings.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brightblu.parc.core.data.LauncherAppIndex
import com.brightblu.parc.core.data.LayoutRepositoryImpl
import com.brightblu.parc.core.data.backup.AndroidBackupFileStore
import com.brightblu.parc.feature.settings.backup.BackupController
import com.brightblu.parc.feature.settings.backup.LayoutBackupScreen
import com.brightblu.parc.feature.settings.backup.LayoutBackupService
import com.brightblu.parc.feature.settings.hidden.HiddenApps
import com.brightblu.parc.feature.settings.hidden.HiddenAppsController
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Debug-only host for the two T17 review screens, mirroring `app/`'s review activities and the T16
 * [SkinReviewActivity]. The production host is `:app`; this activity exists so the backup and
 * hidden apps screens can be captured and a real restore exercised on the emulator without a
 * release manifest change. It is absent from release builds.
 *
 * `adb shell am start -n com.brightblu.parc/com.brightblu.parc.feature.settings.debug.SettingsReviewActivity`
 * opens the backup screen; adding `--es screen hidden` opens hidden apps (handled on a re-delivered
 * intent too).
 */
class SettingsReviewActivity : ComponentActivity() {

    private var screen by mutableStateOf(ScreenBackup)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val metrics = resources.displayMetrics
        val layout = LayoutRepositoryImpl.create(this) {
            RingLayout(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density).arcs
        }
        val index = LauncherAppIndex.create(this, scope)
        // Same cold-start order as AppGraph: seed the layout once the installed list is known.
        scope.launch {
            index.apps.collect { apps ->
                if (apps.isNotEmpty()) {
                    layout.load(apps)
                    layout.syncInstalled(apps)
                }
            }
        }
        val backupController = BackupController(
            LayoutBackupService(layout, AndroidBackupFileStore(this)),
            scope,
        )
        val hiddenController = HiddenAppsController(layout, index.apps, scope)
        screen = intent.getStringExtra(ScreenExtra) ?: ScreenBackup
        setContent {
            val skin = Skin.Midnight
            if (screen == ScreenHidden) {
                HiddenApps(hiddenController, skin, Modifier.fillMaxSize())
            } else {
                LayoutBackupScreen(backupController, skin, Modifier.fillMaxSize())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screen = intent.getStringExtra(ScreenExtra) ?: ScreenBackup
    }

    companion object {
        const val ScreenExtra = "screen"
        const val ScreenBackup = "backup"
        const val ScreenHidden = "hidden"
    }
}
