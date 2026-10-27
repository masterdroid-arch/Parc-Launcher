package com.brightblu.parc.core.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.brightblu.parc.core.data.icons.IconPackRepository
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production [AppIndex]. All behaviour lives in pure collaborators; this class only wires them to
 * Android and to disk. The apps flow is seeded from the persisted snapshot so the first frame can
 * paint before LauncherApps answers, then kept current by the debounced index core.
 */
class LauncherAppIndex internal constructor(
    source: LauncherSource,
    private val scope: CoroutineScope,
    private val snapshot: SnapshotStore,
    iconProducer: IconProducer<Bitmap>,
    private val ioDispatcher: CoroutineDispatcher,
    debounceMillis: Long,
    private val logWarn: (String) -> Unit,
    /** Icon pack source; null keeps the plain app icons (the T04 behaviour). */
    val iconPacks: IconPackRepository? = null,
) : AppIndex {
    private val core = AppIndexCore(
        source = source,
        scope = scope,
        debounceMillis = debounceMillis,
        dispatcher = ioDispatcher,
        initial = snapshot.read(),
    )
    override val apps: StateFlow<List<AppEntry>> = core.apps

    /**
     * Profiles behind [apps] (T74). Empty until the first scan; then it names the personal user and
     * every work/other profile the source exposed, with the paused flag for each.
     */
    val profiles: StateFlow<ProfileSet> = core.profiles

    private val _iconsLoaded = MutableSharedFlow<AppId>(extraBufferCapacity = IconEmitBuffer)
    val iconsLoaded: SharedFlow<AppId> = _iconsLoaded.asSharedFlow()

    private val icons = IconCacheManager(
        producer = iconProducer,
        sizeOf = { it.byteCount },
        labelOf = { id -> apps.value.firstOrNull { it.id == id }?.label },
        scope = scope,
        dispatcher = ioDispatcher,
        onLoaded = { _iconsLoaded.tryEmit(it) },
        logWarn = logWarn,
    )

    /** OPUS2: the icon budget follows the installed app count, so a full scene never thrashes. */
    private val budgetJob = scope.launch {
        apps.collect { icons.fitAppCount(it.size, com.brightblu.parc.model.Metrics.iconBitmapPx) }
    }

    private val launcher = AppLauncher(source, logWarn)
    private val shortcutProvider = ShortcutProvider(source)

    /**
     * A pack change invalidates every cached bitmap: the cached ones came from the old source.
     * Only the ids that were actually cached are announced, so the UI re-requests those and nothing
     * else. The restored selection may arrive after the cache is built, so the comparison is against
     * the value the cache was built with rather than "drop the first emission".
     */
    private var cachedIconSelection: String? = iconPacks?.selection?.value
    private val selectionJob = iconPacks?.let { packs ->
        scope.launch {
            packs.selection.collect { value ->
                if (value != cachedIconSelection) {
                    cachedIconSelection = value
                    icons.clear().forEach { id -> _iconsLoaded.tryEmit(id) }
                }
            }
        }
    }

    private val snapshotJob = scope.launch {
        core.apps.drop(1).collect { entries ->
            withContext(ioDispatcher) { snapshot.write(entries) }
        }
    }

    /** Stops the index and its snapshot writer. The app index lives for the process lifetime. */
    fun close() {
        snapshotJob.cancel()
        selectionJob?.cancel()
        budgetJob.cancel()
        iconPacks?.close()
        core.close()
    }

    /** Non-blocking cache lookup; schedules a background load on a miss and returns null. */
    override fun icon(id: AppId, sizePx: Int): Bitmap? = icons.get(id, sizePx)

    /** Warms the cache for the given apps at the default size. */
    fun preload(ids: List<AppId>) = icons.preload(ids)

    /** OPUS3: warms the cache at a chosen raster size (the ring uses Metrics.iconBitmapPx). */
    fun preload(ids: List<AppId>, sizePx: Int) = icons.preload(ids, sizePx)

    /** Re-reads apps and profiles without waiting for a package event (for example on resume). */
    fun refresh() = core.refresh()

    override fun launch(id: AppId) {
        launcher.launch(id)
    }

    /**
     * Launches and reports what happened, so the UI can dim or explain a profile refusal (T75).
     * [launch] keeps the frozen [AppIndex] contract and discards the outcome.
     */
    fun launchWithOutcome(id: AppId): LaunchOutcome = launcher.launch(id)

    override fun shortcuts(id: AppId): List<AppShortcut> = shortcutProvider.shortcuts(id)

    companion object {
        const val LogTag = "Parc"
        const val SnapshotFileName = "app_snapshot.json"
        private const val IconEmitBuffer = 64

        /** Builds the production index for the given app context. */
        fun create(context: Context, scope: CoroutineScope): LauncherAppIndex {
            val appContext = context.applicationContext
            val source = AndroidLauncherSource(appContext)
            val iconPacks = IconPackRepository.create(appContext, scope) { component, sizePx ->
                source.renderIcon(component, source.currentUserSerial, sizePx)
            }
            return LauncherAppIndex(
                source = source,
                scope = scope,
                snapshot = SnapshotStore(File(appContext.filesDir, SnapshotFileName)),
                iconProducer = AndroidIconProducer(source, iconPacks),
                ioDispatcher = Dispatchers.IO,
                debounceMillis = AppIndexCore.DefaultDebounceMillis,
                logWarn = { Log.d(LogTag, it) },
                iconPacks = iconPacks,
            )
        }
    }
}
