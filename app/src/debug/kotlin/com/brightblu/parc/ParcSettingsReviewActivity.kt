package com.brightblu.parc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.launch

/** Debug entry to the production settings host; section scoping is for repeatable visual review. */
class ParcSettingsReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    resources.displayMetrics.density, intent.getFloatExtra("fontScale", 1f))) {
            val skin = when (intent.getStringExtra("skin")) {
                "light" -> FieldAtlas.Light
                "phosphor" -> Phosphor.Skin
                else -> Skin.Midnight
            }
            SideEffect { applyLookWindow(this, LookSettings(), skin) }
            val scope = rememberCoroutineScope()
            val metrics = resources.displayMetrics
            val services by produceState<LauncherServices?>(null) {
                val host = AppGraph.get(applicationContext, RingLayout(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density))
                // Opt-in real-clock boundary fixture; normal review entry never changes modes.
                if (intent.getBooleanExtra("focusScheduleFixture", false)) {
                    val now = java.time.LocalTime.now()
                    val start = (now.hour * 60 + now.minute + 1) % (24 * 60)
                    host.focus.clearManualOverride()
                    host.focus.save(com.brightblu.parc.core.data.focus.FocusMode("Schedule review",
                        priority = 9, hiddenPackages = setOf("com.android.chrome"),
                        schedule = com.brightblu.parc.core.data.focus.FocusSchedule(
                            com.brightblu.parc.core.data.focus.FocusDay.entries.toSet(), start, (start + 1) % (24 * 60))))
                    android.util.Log.i("FocusScheduleReview", "Saved at $now; start=$start; end=${(start + 1) % (24 * 60)}")
                }
                value = host
            }
            services?.let { host ->
                val reviewSkin = host.skins.firstOrNull { it.id == intent.getStringExtra("skinId") } ?: skin
                val ui = remember { ParcUiPrefs(this) }
                var aligned by remember { mutableStateOf(ui.alignAcrossArcs()) }
                var joystick by remember { mutableStateOf(ui.joystickAim()) }
                var doubleTap by remember { mutableStateOf(ui.hubDoubleTap()) }
                var badges by remember { mutableStateOf(ui.notificationBadges()) }
                var allBadges by remember { mutableStateOf(ui.badgesInAllApps()) }
                var work by remember { mutableStateOf(ui.markWorkApps()) }
                val lookStore = remember { ParcLookStore(this) }
                val look by lookStore.look.collectAsState(initial = LookSettings())
                val gestureStore = remember { GestureOptionsStore(this) }
                val gestures by gestureStore.config.collectAsState(initial = GestureConfig())
                var page by remember { mutableStateOf(intent.getStringExtra("page")?.let { raw -> SettingsPage.entries.firstOrNull { it.name == raw } } ?: SettingsPage.Root) }
                val section = intent.getStringExtra("section")?.let { raw -> SettingsSectionId.entries.firstOrNull { it.name == raw } }
                Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                    val optionsId = intent.getStringExtra("hubPageOptions")?.takeIf { it in HubCatalog.pages }
                    if (optionsId != null) {
                        val hubStore = remember { HubConfigStore(this@ParcSettingsReviewActivity) }
                        val config by hubStore.config.collectAsState(initial = HubConfig())
                        var open by remember { mutableStateOf(true) }
                        if (open) HubPageOptionsSheet(optionsId, skin, config,
                            { change -> scope.launch { hubStore.update(change) } },
                            { open = false; finish() }, { page = it; open = false })
                        if (open) return@Box
                    }

                    ParcSettings(host, reviewSkin, aligned, joystick, doubleTap,
                        onSetHubDoubleTap = { doubleTap = it; ui.setHubDoubleTap(it) },
                        gestures = gestures, onSetGesture = { slot, action -> scope.launch { gestureStore.set(slot, action) } },
                        badgesEnabled = badges, badgesInAllApps = allBadges,
                        onSetBadgesEnabled = { badges = it; ui.setNotificationBadges(it) },
                        onSetBadgesInAllApps = { allBadges = it; ui.setBadgesInAllApps(it) },
                        markWorkApps = work, onSetMarkWorkApps = { work = it; ui.setMarkWorkApps(it) },
                        onSetAlignedArcs = { aligned = it; ui.setAlignAcrossArcs(it) },
                        onSetJoystick = { joystick = it; ui.setJoystickAim(it) },
                        onAndroidSettings = {}, look = look,
                        onChangeLook = { change -> scope.launch { lookStore.update(change) } },
                        onClose = { finish() }, initialPage = page, reviewSection = section)
                }
            }
            }
        }
    }
}
