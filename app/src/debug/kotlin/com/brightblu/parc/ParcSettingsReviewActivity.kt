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
                // T178: the same window geometry as MainActivity (full window plus insets), so previews match Home pixel for pixel.
                val window = windowManager.currentWindowMetrics
                val host = AppGraph.get(applicationContext, RingLayout(window.bounds.width() / metrics.density,
                    window.bounds.height() / metrics.density, window.windowInsets.parcInsets(metrics.density)))
                // T180: the settings audit. "setting" is a registry id and "index" the option to choose; the change goes through
                // SettingsRegistry and SettingsWriter, the same path as a tap in Settings. Then Home opens. Works with demo on or off.
                // "check" logs every value as the stores hold it now (the audit reads these lines to prove the save).
                if (intent.getBooleanExtra("check", false)) {
                    val (stored, _) = SettingsSnapshotLoader.load(applicationContext, host)
                    // Written to a file (logcat drops lines when 60 come at once); the audit reads it with run-as.
                    val text = SettingsRegistry.all.filter { it.hasValue }.joinToString("\n") { def ->
                        "value ${def.id}=${def.read!!(stored)} ${def.value(stored)}|shown=${def.shown(stored)}|options=${def.options(stored).joinToString(";")}|effect=${def.effect}"
                    }
                    java.io.File(filesDir, "audit-values.txt").writeText(text + "\n")
                    finish()
                    return@produceState
                }
                intent.getStringExtra("setting")?.let { id ->
                    val (snapshot, writer) = SettingsSnapshotLoader.load(applicationContext, host)
                    val def = SettingsRegistry.byId[id]
                    // "value" picks an option by its label (a skin by its name); "index" by its position.
                    val index = intent.getStringExtra("value")?.let { label -> def?.options(snapshot)?.indexOf(label) }
                        ?: intent.getIntExtra("index", -1)
                    if (def != null && def.hasValue && index >= 0) {
                        val next = def.write!!(snapshot, index)
                        writer.persist(snapshot, next)
                        android.util.Log.i("ParcAudit", "set $id=$index (${def.options(next).getOrNull(index)}) read back ${def.read!!(next)}")
                    } else android.util.Log.w("ParcAudit", "unknown or valueless setting $id")
                    if (!intent.getBooleanExtra("stay", false)) {
                        startActivity(android.content.Intent(this@ParcSettingsReviewActivity, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                        return@produceState
                    }
                }
                // T176: "wallpaper=palms" puts the palm sunset test picture in place as the chosen picture (what the owner uses).
                if (intent.getStringExtra("wallpaper") == "palms") {
                    val target = java.io.File(applicationContext.filesDir, "wallpaper/home.jpg").also { it.parentFile?.mkdirs() }
                    assets.open("wallpaper_palms.jpg").use { input -> target.outputStream().use { input.copyTo(it) } }
                    ParcLookStore(applicationContext).update { it.copy(wallpaper = WallpaperStyle.Image, wallpaperImageRevision = System.currentTimeMillis()) }
                    host.prefs.setOnboardingCompleted(true)
                    if (!intent.getBooleanExtra("hubAppearanceFixture", false)) {
                        startActivity(android.content.Intent(this@ParcSettingsReviewActivity, MainActivity::class.java))
                        finish()
                        return@produceState
                    }
                }
                // T168: demo-only fixture writes real stores, then opens the production Home.
                if (DemoMode.enabled && intent.getBooleanExtra("hubAppearanceFixture", false)) {
                    intent.getStringExtra("skinId")?.let { host.prefs.setSkinId(it) }
                    HubAppearanceStore(applicationContext).update {
                        HubAppearance(HubColour.decode(intent.getStringExtra("arcColour")),
                            HubColour.decode(intent.getStringExtra("statsColour")),
                            intent.getStringExtra("glass")?.toBooleanStrictOrNull(),
                            // T171: "arcOpacity" and "statsOpacity" (40 to 100) set each hub's own value; absent leaves it unset.
                            intent.getStringExtra("arcOpacity")?.toIntOrNull(), intent.getStringExtra("statsOpacity")?.toIntOrNull(),
                            // T173: "refraction=false" turns the lens edge off (default on).
                            intent.getStringExtra("refraction")?.toBooleanStrictOrNull() ?: true)
                    }
                    // T181: "panelOpacity" puts an old (1.1.0) Panel opacity into the Look document, to prove the migration.
                    intent.getStringExtra("panelOpacity")?.toIntOrNull()?.let { old ->
                        ParcLookStore(applicationContext).update { it.copy(panelOpacityPercent = old) }
                    }
                    host.prefs.setOnboardingCompleted(true)
                    // T169: "wallpaper=stripes" puts the debug-only striped picture in place as the Image wallpaper, with no dim or
                    // scrim of its own; "wallpaper=system" selects the system wallpaper. Anything else leaves the look alone.
                    when (intent.getStringExtra("wallpaper")) {
                        "stripes" -> {
                            val target = java.io.File(applicationContext.filesDir, "wallpaper/home.jpg").also { it.parentFile?.mkdirs() }
                            assets.open("t169_stripes.png").use { input -> target.outputStream().use { input.copyTo(it) } }
                            ParcLookStore(applicationContext).update {
                                it.copy(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 0, keepTextReadable = false, wallpaperReadability = false, imageReadabilityScrim = false,
                                    wallpaperImageRevision = System.currentTimeMillis())
                            }
                        }
                        // T172: photo-like pictures (bright sky over a dark ground, only the sky, only the ground); "readable=false" turns "Keep text readable" off.
                        "photo", "photo-bright", "photo-dark" -> {
                            val asset = when (intent.getStringExtra("wallpaper")) { "photo-bright" -> "t172_photo_bright.jpg"; "photo-dark" -> "t172_photo_dark.jpg"; else -> "t172_photo.jpg" }
                            val target = java.io.File(applicationContext.filesDir, "wallpaper/home.jpg").also { it.parentFile?.mkdirs() }
                            assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
                            val readable = intent.getStringExtra("readable") != "false"
                            ParcLookStore(applicationContext).update {
                                it.copy(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 0, keepTextReadable = readable, wallpaperReadability = readable, imageReadabilityScrim = readable, // the one "Keep text readable" switch (LookJson.migrate)
                                    wallpaperImageRevision = System.currentTimeMillis())
                            }
                        }
                        "system" -> ParcLookStore(applicationContext).update { it.copy(wallpaper = WallpaperStyle.System, keepTextReadable = false, wallpaperReadability = false, wallpaperDimPercent = 0) }
                        "black" -> ParcLookStore(applicationContext).update { it.copy(wallpaper = WallpaperStyle.Black) }
                    }
                    startActivity(android.content.Intent(this@ParcSettingsReviewActivity, MainActivity::class.java))
                    finish()
                    return@produceState
                }
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
                // T178: "fullPreview=true" shows the Settings skin preview of the ACTIVE skin full screen, at Home's own size,
                // so a screenshot of it can be compared pixel for pixel with a screenshot of Home.
                if (intent.getBooleanExtra("fullPreview", false)) {
                    val settings by host.prefs.settings.collectAsState(initial = com.brightblu.parc.core.data.ParcSettings())
                    val look by remember { ParcLookStore(this) }.look.collectAsState(initial = null)
                    val active = host.skins.firstOrNull { it.id == settings.skinId }
                    if (active != null && look != null) {
                        SideEffect { applyLookWindow(this, LookSettings(), active) }
                        SkinFullPreview(active, host, look!!, onUse = {}, onBack = { finish() })
                    }
                    return@let
                }
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
                val row = intent.getStringExtra("row")
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
                        onClose = { finish() }, initialPage = page, initialRow = row)
                }
            }
            }
        }
    }
}
