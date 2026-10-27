package com.brightblu.parc

import android.content.Context
import com.brightblu.parc.core.data.LauncherAppIndex
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.stats.AndroidDeviceStatsSource
import com.brightblu.parc.core.data.usage.UsageLayerManager
import com.brightblu.parc.core.data.usage.UsageRepositoryImpl
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchIndexer
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned services, created only by the normal HOME host, never by recovery. */
object AppGraph {
    private var services: LauncherServices? = null

    /** T96: re-reads the system colour palette and night mode (resume and configuration change). */
    fun refreshWallpaper(context: Context) { services?.wallpaperSkin?.refresh(context) }

    /** T98: Parc left the foreground, so the unlock window ends. */
    fun folderLockHostLeft() { services?.folderLock?.onHostLeft() }

    /** T98: back in the foreground; an expired window is closed even if its timer did not fire. */
    fun checkFolderLock() { services?.folderLock?.expireIfDue() }

    suspend fun get(context: Context, geometry: RingLayout): LauncherServices {
        services?.let { it.geometry = geometry; return it }
        val cache = StartupCache(File(context.filesDir, "startup-snapshot.json"))
        // T122: demo mode uses its own in-memory layout; the real startup snapshot is never read.
        val restored = if (DemoMode.enabled) null else withContext(Dispatchers.IO) { cache.read() }
        return (services ?: LauncherServices(context.applicationContext, geometry, restored, cache)
            .also { services = it }).also { it.geometry = geometry }
    }
}

class LauncherServices internal constructor(context: Context, initialGeometry: RingLayout,
    restored: StartupSnapshot?, private val startupCache: StartupCache) {
    /**
     * OPUS6 (audit F5): a failure in one background job is logged and that job ends; it no longer
     * kills the process at every start (which led to safe mode with no way out).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        ParcLog.e("Parc", "background job failed", error)
    })
    @Volatile var geometry: RingLayout = initialGeometry
    val appIndex = LauncherAppIndex.create(context, scope)
    /**
     * T122: demo mode swaps the whole layout source. When demo is on the real repository is never
     * constructed, so no real layout row or preference is read or written.
     */
    val layout: LayoutStore = DemoHomeLayout.select(DemoMode.enabled, initialGeometry.arcs) {
        RealLayoutStore.create(context) { geometry.arcs }
    }
    val shortcutHost: ShortcutHost = LauncherShortcutHost(context)
    private val snapshot = MutableStateFlow(
        if (DemoMode.enabled) StartupSnapshot(layout.layout.value, DemoHomeLayout.appsIfDemo(true))
        else restored ?: StartupSnapshot(ParcLayout(), emptyList())
    )
    val presentation = snapshot.asStateFlow()
    /** T98: app-local settings and the in-memory unlock window for locked folders. */
    internal val uiPrefs = ParcUiPrefs(context)
    internal val folderLock = FolderLock(scope, android.os.SystemClock::elapsedRealtime, uiPrefs::lockTimeout)
    /**
     * T98: the one set every surface filters with. It reads the startup snapshot (the cached layout at
     * cold start, the live one afterwards), so a locked app is hidden before the live layout loads.
     * Empty while the window is open or nothing is locked.
     */
    init {
        com.brightblu.parc.core.data.lock.NotificationLockPolicy.packages = {
            com.brightblu.parc.core.data.lock.LockedApps.lockedPackages(snapshot.value.apps,
                snapshot.value.layout.folders.filter { !folderLock.isUnlocked(it.id) }, false)
        }
        folderLock.onChange = { com.brightblu.parc.core.data.lock.NotificationLockPolicy.refilter() }
        com.brightblu.parc.core.data.lock.NotificationLockPolicy.refilter()
    }
    val lockedPackages: StateFlow<Set<String>> = combine(snapshot, folderLock.windows) { shown, windows ->
        com.brightblu.parc.core.data.lock.NotificationLockPolicy.refilter()
        com.brightblu.parc.core.data.lock.LockedApps.lockedPackages(shown.apps, shown.layout.folders.filter { !folderLock.isUnlocked(it.id) }, false)
    }.stateIn(scope, SharingStarted.Eagerly, com.brightblu.parc.core.data.lock.LockedApps.lockedPackages(
        snapshot.value.apps, snapshot.value.layout.folders, false))
    /** T98: installed apps without the hidden ones; for the stats cards, settings pickers and Comms. */
    val unlockedApps: StateFlow<List<AppEntry>> = combine(appIndex.apps, lockedPackages) { all, hidden ->
        com.brightblu.parc.core.data.lock.LockedApps.visibleApps(all, hidden)
    }.stateIn(scope, SharingStarted.Eagerly, appIndex.apps.value)
    private val usageImpl = UsageRepositoryImpl.create(
        context = context,
        scope = scope,
        folders = { layout.layout.value.folders },
        pinned = { layout.layout.value.pins },
        installedIds = { appIndex.apps.value.map { it.id } },
        labels = { appIndex.apps.value.associate { it.id to it.label } },
    )
    val usage: UsageRepository = usageImpl
    /** Cold-start usage-layer re-rank (T34); the ring plays its swaps (T35). */
    val usageLayer: UsageLayerManager = UsageLayerManager.create(
        context = context,
        repository = layout,
        scores = usageImpl,
        installed = { com.brightblu.parc.core.data.lock.LockedApps.visibleApps(appIndex.apps.value,
            com.brightblu.parc.core.data.lock.LockedApps.lockedPackages(appIndex.apps.value, layout.layout.value.folders, false)).map { it.id } },
    )
    private val usageLayerRan = AtomicBoolean(false)
    val searchEngine = LabelSearchEngine()
    /** T73: one repository and one process timer for focus modes (T72). */
    val focus = com.brightblu.parc.core.data.focus.FocusRepository.create(context, scope)
    init {
        if (DemoMode.enabled) {
            // T123: the sample is written only to the namespaced demo DataStore. DND binding is
            // skipped so no real automatic rule can be created from demo data.
            scope.launch { DemoFocusModes.install(true, RepositoryFocusSampleSink(focus)) }
        } else bindFocusDnd(context, focus, scope)
    }
    /** Search sees the view-level focus filter; launching still goes through [appIndex]. */
    val focusIndex: AppIndex = FocusAppIndex(appIndex, focus.state, scope, layout.layout, lockedPackages)
    val prefs = ParcPrefs.create(context)
    val comms = com.brightblu.parc.core.data.comms.CommsRepositories.create(context, prefs, scope)
    /** T30: the active skin drives every surface; the picker edits [prefs] through T16's screen. */
    val customSkins = com.brightblu.parc.core.data.skins.CustomSkinStore(java.io.File(context.filesDir, "skins"))
    private val skinCatalog = com.brightblu.parc.core.data.skins.SkinCatalogLoader.parse(
        com.brightblu.parc.core.data.skins.AndroidSkinAssets.read(context))
    /** T96: the generated Wallpaper skin; re-read from the system on resume and configuration change. */
    val wallpaperSkin = com.brightblu.parc.core.data.skins.WallpaperSkinSource(
        com.brightblu.parc.core.data.skins.AndroidDynamicPalette.read(context), prefs.settings, skinCatalog.skins, scope)
    val skinProvider = com.brightblu.parc.core.data.skins.SkinRepository.create(context, prefs, scope, customSkins,
        skinCatalog, wallpaperSkin)
    val skins: List<Skin> get() = skinProvider.available
    val stats = AndroidDeviceStatsSource(context, scope)
    val statsAlerts = com.brightblu.parc.core.data.stats.StatsAlertRepository.create(context, scope)
    val statsArchive = com.brightblu.parc.core.data.stats.StatsArchiveRepository.create(context, scope)
    internal val statsAlertUi = StatsAlertController(stats.stats, statsAlerts.thresholds, StatsAlertUiStore(context), scope)
    val powerStats = com.brightblu.parc.core.data.power.AndroidPowerStatsSource(context, scope)
    // T98: these four read the app list through the lock filter, so a locked app has no row, no time and no size.
    val networkStats = com.brightblu.parc.core.data.network.AndroidNetworkStatsRepository.create(context) { unlockedApps.value }
    val lastApp = com.brightblu.parc.core.data.apps.AndroidLastAppTracker.create(context) { unlockedApps.value }
    val screenTime = com.brightblu.parc.core.data.apps.AndroidScreenTimeRepository.create(context) { unlockedApps.value }
    val newApps = com.brightblu.parc.core.data.apps.AndroidNewAppsProvider.create(context, { unlockedApps.value })
    val appStorage = com.brightblu.parc.core.data.storage.AndroidAppStorageRepository.create(context) { unlockedApps.value }
    val volume = com.brightblu.parc.core.data.audio.AndroidVolumeRepository.create(context)
    private val searchIndexer = SearchIndexer(focusIndex, layout, usage, searchEngine, scope)
    private val revision = MutableStateFlow(0L)
    val iconRevision = revision.asStateFlow()
    private val ready = MutableStateFlow(DemoMode.enabled)
    val liveReady = ready.asStateFlow()

    init {
        AppBackup.install(context, customSkins)
        registerScreenOff(context)
        if (!DemoMode.enabled) scope.launch {
            // Serialize reconciliation and cache writes; never cache the repository's initial empty value.
            combine(appIndex.apps, layout.layout) { apps, _ -> apps }.collect { apps ->
                if (apps.isNotEmpty()) {
                    // OPUS6 (audit F5): one failed pass is logged and retried on the next change.
                    val ok = try {
                        withContext(Dispatchers.IO) {
                            layout.load(apps)
                            layout.syncInstalled(apps)
                            usageLayer.removeLockedResidents()
                        }
                        true
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        ParcLog.e("Parc", "layout reconcile failed", error)
                        false
                    }
                    // OPUS6 (audit F3): never publish or cache a layout that was not read.
                    if (!ok || !layout.loaded) return@collect
                    val next = StartupSnapshot(layout.layout.value, apps)
                    snapshot.value = next
                    ready.value = true
                    try { withContext(Dispatchers.IO) { startupCache.write(next) } }
                    catch (cancellation: kotlinx.coroutines.CancellationException) { throw cancellation }
                    catch (error: Exception) { ParcLog.e("Parc", "startup snapshot write failed", error) }
                    // Once per process, off the main thread, after the layout is loaded (T34).
                    if (usageLayerRan.compareAndSet(false, true)) {
                        scope.launch(Dispatchers.Default) { usageLayer.refresh() }
                    }
                }
            }
        }
        scope.launch { appIndex.iconsLoaded.collect { revision.value++ } }
    }
}

/** T98: the unlock ends when the screen turns off. Registered once for the process; never throws. */
private fun LauncherServices.registerScreenOff(context: Context) {
    try {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                try { folderLock.onScreenOff() } catch (e: Exception) { ParcLog.e("Parc", "Screen-off relock failed", e) }
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED)
    } catch (e: Exception) {
        ParcLog.e("Parc", "Screen-off receiver not registered", e)
    }
}

/** No-op [UsageRepository] for debug fixtures that never need a real launch log. */
internal class PendingUsageRepository : UsageRepository {
    override fun recordLaunch(id: AppId) = Unit
    override fun recents(limit: Int): Flow<List<AppId>> = flowOf(emptyList())
    override fun rank(folderId: FolderId): List<AppId> = emptyList()
}
