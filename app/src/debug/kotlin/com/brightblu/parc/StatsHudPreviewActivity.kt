package com.brightblu.parc

import com.brightblu.parc.core.data.apps.*
import com.brightblu.parc.core.data.storage.*
import com.brightblu.parc.core.data.audio.*
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.core.data.power.*
import com.brightblu.parc.core.data.network.*
import com.brightblu.parc.model.AppId
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.brightblu.parc.core.data.stats.StatsAlertThresholds
import com.brightblu.parc.core.data.stats.StatsArchive
import com.brightblu.parc.core.data.stats.StatsArchiveReading
import com.brightblu.parc.core.data.stats.ChargingState
import com.brightblu.parc.core.data.stats.CpuCluster
import com.brightblu.parc.core.data.stats.DeviceStats
import com.brightblu.parc.core.data.stats.DeviceStatsSource
import com.brightblu.parc.core.data.stats.PlugType
import com.brightblu.parc.core.data.stats.StatsHistory
import com.brightblu.parc.core.data.stats.ThermalStatus
import com.brightblu.parc.core.data.stats.StorageUsage
import com.brightblu.parc.core.data.stats.TemperatureSensor
import com.brightblu.parc.ring.*
import com.brightblu.parc.model.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** ADB-only HUD review host with deterministic System, Power and Network snapshots.
 * `unavailable=true` exercises missing API/usage readings; `skin=cyber` selects the Cyber skin.
 * Paging, compact/expanded state and persisted tile editing use the production UI.
 */
class StatsHudPreviewActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val skin = loadSkin(intent.getStringExtra("skin"))
        applySkinSystemBars(this, skin)
        window.statusBarColor = skin.colors.bg.toInt()
        window.navigationBarColor = skin.colors.bg.toInt()
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        if (intent.getBooleanExtra("alertSettings", false)) {
            val repository = com.brightblu.parc.core.data.stats.StatsAlertRepository.create(this, lifecycleScope)
            setContent {
                Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                    SettingsPageFrame(skin, onBack = { finish() }) {
                        com.brightblu.parc.model.ParcPage(safeTop = false, scroll = false) { StatsAlertSettings(repository, skin) }
                    }
                }
            }
            return
        }
        val expanded = intent.getBooleanExtra("expanded", true)
        val source = FakeStatsSource(intent.getStringExtra("storage"), intent.getBooleanExtra("stale", false), intent.getBooleanExtra("t79", false))
        setContent {
            val baseDensity = LocalDensity.current
            val previewContext = this@StatsHudPreviewActivity
            CompositionLocalProvider(LocalHubPreview provides intent.getBooleanExtra("t135", false),
                LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, intent.getFloatExtra("fontScale", 1f))) {
            val hubStore = remember { HubConfigStore(previewContext) }
            LaunchedEffect(Unit) {
                intent.getStringExtra("page")?.takeIf { it in HubCatalog.pages }?.let { hubStore.rememberPage(it) }
                intent.getStringExtra("hubSize")?.let { size -> hubStore.update { it.copy(size = HubSize.decode(size)) } }
            }
            val t79 = intent.getBooleanExtra("t79", false)
            var alerts by remember { mutableStateOf(StatsAlertUiState().evaluate(
                source.stats.value.copy(batteryPercent = 10, chargingState = ChargingState.Discharging,
                    ramUsedPercent = 95, batteryTemperatureC = 44f), StatsAlertThresholds.Default, t79)) }
            val archive = remember {
                if (!t79 || intent.getBooleanExtra("emptyHistory", false)) StatsArchive.Empty else {
                    val now = System.currentTimeMillis()
                    var result = StatsArchive.Empty
                    repeat(288) { i -> result = result.record(now - (287 - i) * 300_000L, StatsArchiveReading(
                        batteryPercent = 90 - i / 4, batteryTemperatureC = 32f + (i % 40) / 5f,
                        ramUsedPercent = 50 + i % 35, storageUsedPercent = 60 + i / 20,
                        networkRxTotalBytes = i * 3_000_000L, networkTxTotalBytes = i * 500_000L)) }
                    result
                }
            }
            var isExpanded by remember { mutableStateOf(expanded) }
            BoxWithConstraints((if (intent.getBooleanExtra("short", false)) Modifier.size(360.dp, 800.dp) else Modifier.fillMaxSize()).background(Color(skin.colors.bg.toInt()))) {
                val density = LocalDensity.current
                val geometry = remember(maxWidth, maxHeight) { RingLayout(maxWidth.value, maxHeight.value) }
                val renderer = remember { CanvasRingRenderer() }
                val scene = remember(geometry, intent.getBooleanExtra("phone", false)) {
                    if (intent.getBooleanExtra("phone", false)) {
                        // T83: the old personal seed shape: A3 full of folders, A4 holding only the
                        // gear and one folder, so the outer arc's top position is empty.
                        val slots = mutableListOf<RingSlot>()
                        val names = listOf("AI", "Google", "Media", "Money", "Mine", "Social", "Tools")
                        val a = geometry.arcs
                        for (i in 0 until a[2].slotCapacity) {
                            slots += RingSlot(SlotPosition(a[2].id, i), Slot.Folder(FolderId("f$i")),
                                names[i % names.size])
                        }
                        if (a[3].slotCapacity > 0) slots += RingSlot(SlotPosition(a[3].id, 0),
                            Slot.Action(ActionKind.Settings), "Settings")
                        if (a[3].slotCapacity > 1) slots += RingSlot(SlotPosition(a[3].id, 1),
                            Slot.Folder(FolderId("life")), "Life")
                        RingScenes.home(geometry, slots, "")
                    } else {
                        RingScenes.home(geometry, geometry.arcs.flatMap { arc ->
                            (0 until arc.slotCapacity).map { i ->
                                val label = listOf("Life", "Google", "Tools", "Social")[i % 4]
                                RingSlot(SlotPosition(arc.id, i), Slot.Folder(FolderId("${arc.id}/$i")), label)
                            }
                        }, "")
                    }
                }
                renderer.Field(scene, skin, object : RingCallbacks {
                    override fun onTap(hit: SlotHit) { android.util.Log.i("ParcT57", "arc tap ${hit.position}") }
                    override fun onLongPress(hit: SlotHit) = Unit
                    override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
                })
                StatsModule(
                    hubBottomDp = RingHudBounds.bottomDp(scene,
                        (skin.typography.label.sizeSp + Metrics.labelPaddingVerticalDp * 2) * density.fontScale),
                    hubTopDp = HubPanelLayout.topDp(androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density) / density.density),
                    clock = { ClockHubPage(skin, LookSettings(), "09:41", ClockDetail(), true) },
                    source = source,
                    alertState = alerts,
                    onDismissAlert = { alerts = alerts.dismiss(it) },
                    archive = archive,
                    powerSource = remember { ReviewPowerSource(intent.getBooleanExtra("unavailable", false), t79) },
                    networkRepository = remember { ReviewNetworkRepository(intent.getBooleanExtra("unavailable", false)) },
                    screenTime = remember { ReviewScreenTime(intent.getBooleanExtra("unavailable", false)) },
                    newApps = remember { object : NewAppsProvider {
                        override suspend fun recent(limit: Int) = reviewEntries.take(limit).map { NewApp(it, System.currentTimeMillis()) }
                    } },
                    appStorage = remember { object : AppStorageRepository {
                        override suspend fun read(): StorageResult = if (intent.getBooleanExtra("unavailable", false)) StorageResult.Unavailable
                        else StorageResult.Available(reviewEntries.mapIndexed { i, app -> AppStorage(app.id, app.label, app.packageName, 600_000_000L / (i + 1), 200_000_000L, 20_000_000L) })
                    } },
                    comms = remember { ReviewComms() },
                    volume = remember { ReviewVolume(intent.getBooleanExtra("refuse", false)) },
                    entries = reviewEntries,
                    onLaunch = { id -> reviewEntries.firstOrNull { it.id == id }?.let { entry ->
                        packageManager.getLaunchIntentForPackage(entry.packageName)?.let { startActivity(it) }
                    } },
                    skin = skin,
                    eligible = true,
                    resumed = true,
                    expanded = isExpanded,
                    onToggleExpanded = { isExpanded = !isExpanded },
                )
            }
            }
        }
    }

    private fun loadSkin(id: String?): Skin =
        if (id == "dusk") Skin.Dusk
        else if (id == "phosphor") Phosphor.Skin
        else if (id == "field-atlas-light") FieldAtlas.Light
        else if (id == "field-atlas-dark") FieldAtlas.Dark
        else if (id == "cyber") {
            val json = assets.open("skins/cyber.json").bufferedReader().use { it.readText() }
            SkinJson.decode(json)
        } else {
            Skin.Midnight
        }
}

private class FakeStatsSource(storageMode: String?, stale: Boolean, alerts: Boolean = false) : DeviceStatsSource {
    override val stats: StateFlow<DeviceStats> = MutableStateFlow(
        DeviceStats(
            batteryPercent = if (alerts) 10 else 72,
            chargingState = if (alerts) ChargingState.Discharging else ChargingState.Charging,
            plug = PlugType.USB,
            batteryTemperatureC = if (alerts) 44f else 33.1f,
            batteryCurrentMa = -820f,
            thermalStatus = ThermalStatus.Moderate,
            thermalHeadroom = 0.62f,
            networkRxBytesPerSecond = 2_516_582L,
            networkTxBytesPerSecond = 524_288L,
            ramUsedPercent = if (alerts) 95 else 63,
            cpuClusters = listOf(CpuCluster(listOf(0, 1), 2_000_000), CpuCluster(listOf(2, 3), 1_800_000)),
            // One gauge per threshold colour: cool accent, amber, then magenta.
            cpuTemperatureC = if (stale) null else 68.4f,
            gpuTemperatureC = 48.2f,
            skinTemperatureC = 34.0f,
            cpuLoadPercent = 31.5f,
            storageFreePercent = 31,
            storage = when (storageMode) {
                "null" -> null
                "warning" -> StorageUsage(240_000_000_000L, 256_000_000_000L)
                else -> StorageUsage(118_000_000_000L, 256_000_000_000L)
            },
            staleTemperatureSensors = if (stale) setOf(TemperatureSensor.Cpu) else emptySet(),
            uptimeMillis = 97_200_000L,
            wifiRssiDbm = -52,
            wifiLinkSpeedMbps = 866,
        ),
    )

    override val history: StateFlow<StatsHistory> = MutableStateFlow(
        StatsHistory(
            networkRxBytesPerSecond = listOf(0L, 40L, 120L, 90L, 250L, 180L, 420L, 300L, 210L, 380L, 260L, 190L),
            networkTxBytesPerSecond = listOf(0L, 20L, 45L, 30L, 70L, 55L, 90L, 60L, 40L, 75L, 50L, 35L),
        ),
    )

    override fun setActive(active: Boolean) = Unit
}

/** T53 deterministic review data, including a separate denied/missing-data fixture. */
private class ReviewPowerSource(unavailable: Boolean, alerts: Boolean = false) : PowerStatsSource {
    override val stats = MutableStateFlow(if (unavailable) PowerStats.Empty else PowerStats(
        chargingPower = if (alerts) ChargingPower(4.2f, PowerDirection.Discharging) else ChargingPower(18.6f, PowerDirection.Charging),
        chargeTimeRemainingMillis = if (alerts) null else 4_920_000L,
        health = BatteryHealth.Good, cycleCount = 243,
        temperatureHistoryC = List(60) { (if (alerts) 41.2f else 31f) + it * 0.04f + (it % 7) * 0.15f },
        screenOnTodayMillis = 13_020_000L,
    ))
    override fun setActive(active: Boolean) = Unit
}

private class ReviewNetworkRepository(private val unavailable: Boolean) : NetworkStatsRepository {
    override suspend fun read() = NetworkUsageStats(
        today = if (unavailable) TodayDataUsage.Unavailable else TodayDataUsage.Available(
            wifi = DataTotals(2_150_000_000L, 310_000_000L),
            mobile = DataTotals(180_000_000L, 24_000_000L),
            topApps = listOf("YouTube", "Chrome", "Spotify", "WhatsApp", "Maps").mapIndexed { i, label ->
                AppDataUsage(AppId("review/$i"), label, "review.$i", 500_000_000L / (i + 1), 20_000_000L / (i + 1))
            },
        ),
        link = if (unavailable) NetworkLink() else NetworkLink(-52, 866, MobileNetworkType.Lte),
    )
}

private val reviewEntries = listOf(
    AppEntry(AppId("com.google.android.deskclock/com.android.deskclock.DeskClock"), "Clock", "com.google.android.deskclock", true, 0),
    AppEntry(AppId("com.android.chrome/com.google.android.apps.chrome.Main"), "Chrome", "com.android.chrome", true, 0),
    AppEntry(AppId("com.android.calculator2/.Calculator"), "Calculator", "com.android.calculator2", true, 0),
    AppEntry(AppId("com.android.settings/.Settings"), "Settings", "com.android.settings", true, 0),
    AppEntry(AppId("com.brightblu.parc.t54fixture/.MainActivity"), "T54 sample", "com.brightblu.parc.t54fixture", false, 0),
)
private class ReviewScreenTime(private val unavailable: Boolean) : ScreenTimeRepository {
    override suspend fun read(): ScreenTimeResult = if (unavailable) ScreenTimeResult.Unavailable else ScreenTimeResult.Available(
        reviewEntries.mapIndexed { i, a -> AppScreenTime(a.id, a.label, a.packageName, 3_600_000L / (i + 1), 25_200_000L / (i + 1)) },
        8_220_000L, 57_540_000L)
}
private class ReviewVolume(private val refuse: Boolean) : VolumeRepository {
    override val state = MutableStateFlow(VolumeState(VolumeStream.All.map { StreamVolume(it, 5, 15) }, "Pixel speaker · review"))
    override suspend fun refresh() = Unit
    override fun setVolume(stream: VolumeStream, index: Int): Boolean {
        if (refuse) return false
        state.value = state.value.copy(levels = state.value.levels.map { if (it.stream == stream) it.copy(index = index.coerceIn(0, it.maxIndex)) else it })
        return true
    }
    override fun setFraction(stream: VolumeStream, fraction: Float) = setVolume(stream, VolumeScale.indexForFraction(fraction, 15))
}
