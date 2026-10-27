package com.brightblu.parc.feature.settings.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.skins.AndroidSkinAssets
import com.brightblu.parc.core.data.skins.SkinCatalog
import com.brightblu.parc.core.data.skins.SkinCatalogLoader
import com.brightblu.parc.feature.settings.skins.ParcSettingsScreen
import com.brightblu.parc.feature.settings.skins.SettingsController
import com.brightblu.parc.feature.settings.skins.SkinComparisonScreen
import com.brightblu.parc.feature.settings.skins.asSettingsStore
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Debug-only host for the two T16 review screens, mirroring `app/`'s RingReview/Spike activities.
 * The production host is `app/`; this activity exists so S14 and S15 can be captured on the
 * emulator without a release manifest change. It is absent from release builds.
 *
 * `adb shell am start -n com.brightblu.parc/com.brightblu.parc.feature.settings.debug.SkinReviewActivity`
 * opens S14; adding `--es screen compare` opens S15 (handled on a re-delivered intent too).
 */
class SkinReviewActivity : ComponentActivity() {

    private var compare by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = SkinCatalogLoader.parse(AndroidSkinAssets.read(this))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = SettingsController(ParcPrefs.create(this).asSettingsStore(), catalog.skins, scope)
        compare = intent.getStringExtra(ScreenExtra) == ScreenCompare
        setContent {
            val state by controller.state.collectAsState()
            val skin = catalog.byId(state.selectedSkinId)
            if (compare) {
                SkinComparisonScreen(s15Pair(catalog), skin, Modifier.fillMaxSize())
            } else {
                ParcSettingsScreen(
                    state = state,
                    skin = skin,
                    onSelectSkin = controller::selectSkin,
                    onSelectHaptics = controller::selectHaptics,
                    onSetClockVisible = controller::setClockVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        compare = intent.getStringExtra(ScreenExtra) == ScreenCompare
    }

    companion object {
        const val ScreenExtra = "screen"
        const val ScreenCompare = "compare"

        /**
         * The pair S15 compares (DESIGN §5): Midnight against Dusk. Cyber and any later skin join
         * the picker, not this screen, so the documented comparison does not change when a skin is
         * added.
         */
        fun s15Pair(catalog: SkinCatalog): List<Skin> =
            catalog.skins.filter { it.id == MidnightId || it.id == DuskId }

        private const val MidnightId = "midnight"
        private const val DuskId = "dusk"
    }
}

