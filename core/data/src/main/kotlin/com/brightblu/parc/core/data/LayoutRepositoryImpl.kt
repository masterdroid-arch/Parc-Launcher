package com.brightblu.parc.core.data

import android.content.Context
import android.util.Log
import com.brightblu.parc.core.data.firstrun.AndroidInstalledApps
import com.brightblu.parc.core.data.firstrun.AndroidMainAppSuggestions
import com.brightblu.parc.core.data.firstrun.FirstRunSeedSource
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Production [LayoutRepository] over the layout tables.
 *
 * Threading: every mutation takes one mutex, so a layout is never half-applied; [layout] is a
 * [StateFlow] published synchronously and is safe to read from any thread. Reads of the model are
 * immutable snapshots. I/O happens through [store] on its own dispatcher.
 *
 * Cold start: [load] publishes the persisted layout, or seeds one when the tables are empty. Seeding
 * waits until an installed app list is known, so an empty first frame can never be persisted as an
 * empty layout. Every mutation re-reads its result through [LayoutMapper], which means the value in
 * memory is always identical to what a restart would load.
 *
 * Generation (T34): the stored layout carries a generation. A stored layout from an older generation
 * is backed up to [LayoutBackupStore] once and then replaced by the current seed; a layout whose
 * marker cannot be read is left alone instead of being reseeded. T62 keeps the generation at 2 and
 * builds the seed from the installed apps, so an existing install is never reseeded.
 *
 * Main apps (T62): the picker writes a list and a revision to DataStore. [applyPendingMainApps]
 * applies anything newer than the last applied revision exactly once, so an edit reaches Home
 * without a restart and a cold start finishes a save that was interrupted by a kill.
 */
class LayoutRepositoryImpl internal constructor(
    private val store: LayoutStore,
    private val arcSpecs: () -> List<ArcSpec>,
    private val seed: suspend (List<AppEntry>) -> ParcLayout,
    private val logWarn: (String) -> Unit,
    private val generationStore: LayoutGenerationStore,
    private val backupStore: LayoutBackupStore,
    private val seedVersion: Int = SeedGeneration.Current,
    private val mainAppsStore: MainAppsStore? = null,
    private val firstRunStore: FirstRunStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : LayoutRepository {

    private val _layout = MutableStateFlow(ParcLayout(version = LayoutVersion))
    override val layout: StateFlow<ParcLayout> = _layout.asStateFlow()

    private val mutex = Mutex()
    private var installed: List<AppEntry> = emptyList()
    private var ready = false

    private var unreadableKept = false

    /** OPUS6 (audit F3): true once the stored layout was read (or seeded); false after a failed read. */
    val loaded: Boolean get() = ready

    /**
     * Publishes the persisted layout. On a device that has never run Parc the seed is filtered by
     * [installed] (missing apps are skipped) and written; while [installed] is empty nothing is
     * persisted and a later call seeds instead. Safe to call repeatedly; only the first call does work.
     */
    suspend fun load(installed: List<AppEntry>) = mutex.withLock {
        this.installed = installed
        ensureReadyLocked()
    }

    /**
     * Reconciles the layout with the installed apps (install, uninstall, label change). Slots never
     * move; apps that are gone leave empty slots, unseen user apps are prepended to the New folder.
     */
    suspend fun syncInstalled(installed: List<AppEntry>) = mutex.withLock {
        this.installed = installed
        ensureReadyLocked()
        if (!ready) return@withLock
        val baseline = try {
            firstRunStore?.seededAt()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
        val next = LayoutSync.reconcile(_layout.value, installed, NewFolderId, NewFolderName, baseline)
        if (next != _layout.value) persistLocked(next)
    }

    /** Atomic transformations for pin placement, removal and count migration. */
    suspend fun updateLayout(change: (ParcLayout) -> ParcLayout) = edit(change)

    override suspend fun pin(id: AppId, position: SlotPosition) = edit { layout ->
        layout
            .withSlot(position, Slot.App(id))
            .copy(pins = layout.pins + id)
    }

    override suspend fun unpin(id: AppId) = edit { layout ->
        // The slot keeps its app: unpin only clears the pin flag (T01 contract note).
        layout.copy(pins = layout.pins - id)
    }

    override suspend fun move(from: SlotPosition, to: SlotPosition) = edit { layout ->
        layout.requireArc(from.arcId)
        layout.requireArc(to.arcId)
        requireIndex(from.index)
        requireIndex(to.index)
        val moved = layout.slotsOf(from.arcId)[from.index]
        layout.withSlot(from, layout.slotsOf(to.arcId)[to.index]).withSlot(to, moved)
    }

    override suspend fun createFolder(id: FolderId, name: String) = edit { layout ->
        require(layout.folders.none { it.id == id }) { "Folder already exists: ${id.value}" }
        requireFolderName(name)
        layout.copy(folders = layout.folders + Folder(id = id, name = name))
    }

    override suspend fun renameFolder(id: FolderId, name: String) = edit { layout ->
        require(layout.folders.any { it.id == id }) { "Unknown folder: ${id.value}" }
        requireFolderName(name)
        layout.copy(folders = layout.folders.map { if (it.id == id) it.copy(name = name) else it })
    }

    override suspend fun addToFolder(folderId: FolderId, id: AppId) = edit { layout ->
        require(layout.folders.any { it.id == folderId }) { "Unknown folder: ${folderId.value}" }
        layout.copy(folders = layout.folders.map { folder ->
            if (folder.id == folderId) folder.copy(appIds = (folder.appIds + id).distinct()) else folder
        })
    }

    override suspend fun removeFromFolder(folderId: FolderId, id: AppId) = edit { layout ->
        require(layout.folders.any { it.id == folderId }) { "Unknown folder: ${folderId.value}" }
        layout.copy(folders = layout.folders.map { folder ->
            if (folder.id == folderId) folder.copy(appIds = folder.appIds - id) else folder
        })
    }

    override suspend fun hide(id: AppId) = edit { layout -> layout.copy(hidden = layout.hidden + id) }

    override suspend fun unhide(id: AppId) = edit { layout -> layout.copy(hidden = layout.hidden - id) }

    /** Versioned JSON of the current layout ([LayoutJson]); the backup file format (F-15). */
    override suspend fun exportLayout(): String = LayoutJson.encode(_layout.value)

    /**
     * Replaces the layout from versioned JSON. A malformed, unsupported or foreign document throws
     * and nothing changes: restoring a backup made for different arcs must fail loudly (T17) instead
     * of silently dropping slots.
     */
    override suspend fun importLayout(source: String) = edit { _ ->
        val imported = LayoutJson.decode(source)
        val known = arcSpecs().mapTo(mutableSetOf()) { it.id }
        val unknown = imported.arcs.filter { it.slots.isNotEmpty() && it.arc.id !in known }.map { it.arc.id }
        require(unknown.isEmpty()) { "Imported layout uses arcs this build does not have: $unknown" }
        imported
    }

    /**
     * Replaces the four innermost slots with [ids], in order (T62). Missing apps and duplicates are
     * dropped; an empty list clears the arc. Pins are moved with the apps. Only the A1 arc changes.
     */
    suspend fun applyMainApps(ids: List<AppId>) = mutex.withLock {
        ensureReadyLocked()
        check(ready) { "Layout not loaded; call load() before mutating" }
        applyMainAppsLocked(ids)
    }

    /**
     * Applies a picker write that landed after the last cold start (T62). Called by the watcher this
     * class starts in [create]; safe to call at any time, and a no-op until the layout is loaded.
     */
    suspend fun applyPendingMainApps() = mutex.withLock {
        if (!ready) return@withLock
        applyPendingMainAppsLocked()
    }

    private suspend fun applyMainAppsLocked(ids: List<AppId>) {
        if (installed.isEmpty()) return
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        val chosen = ids.filter { it in installedIds }.distinct().take(MainAppsSlotCount)
        val current = _layout.value
        val a1 = current.arcs.firstOrNull { it.arc.id == MainAppsArcId } ?: return
        val oldIds = a1.slots.values.filterIsInstance<Slot.App>().mapTo(mutableSetOf()) { it.id }
        val nextArcs = current.arcs.map { assignment ->
            if (assignment.arc.id == MainAppsArcId) {
                assignment.copy(slots = chosen.mapIndexed { index, id -> index to Slot.App(id) }.toMap())
            } else {
                assignment
            }
        }
        val elsewhere = nextArcs.asSequence()
            .filter { it.arc.id != MainAppsArcId }
            .flatMap { it.slots.values.asSequence() }
            .filterIsInstance<Slot.App>()
            .mapTo(mutableSetOf()) { it.id }
        val next = current.copy(
            arcs = nextArcs,
            pins = (current.pins - (oldIds - chosen.toSet() - elsewhere)) + chosen,
        )
        if (next != current) persistLocked(next)
    }

    private suspend fun applyPendingMainAppsLocked() {
        val store = mainAppsStore ?: return
        try {
            val applied = store.appliedRevision()
            val update = store.pending(applied) ?: return
            applyMainAppsLocked(update.ids)
            store.markApplied(update.revision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            logWarn("main apps apply failed")
        }
    }

    private suspend fun edit(block: (ParcLayout) -> ParcLayout) = mutex.withLock {
        ensureReadyLocked()
        check(ready) { "Layout not loaded; call load() before mutating" }
        persistLocked(block(_layout.value))
    }

    private suspend fun ensureReadyLocked() {
        if (ready) return
        ensureLoadedLocked()
        if (ready) applyPendingMainAppsLocked()
    }

    private suspend fun ensureLoadedLocked() {
        // OPUS6 (audit F3): a failed read is NOT an empty store. Empty rows mean "never written"
        // and lead to the seed, so treating an I/O error, a damaged table or a Room check failure as
        // empty would write the starter layout over the real one. Stay not ready instead: the next
        // load (every app-list change, and the next start) tries again, and nothing is written.
        val rows = try {
            store.read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            logWarn("layout read failed; keeping the stored layout and retrying later")
            // The screen is never left empty: the starter layout is SHOWN (in memory only, never
            // written, edits refused until a read succeeds). The host keeps showing its cached copy
            // of the real layout when it has one.
            if (installed.isNotEmpty() && _layout.value.arcs.all { it.slots.isEmpty() }) _layout.value = seed(installed)
            return
        }
        // OPUS6 (audit F5): rows this build cannot read are kept in a backup file before the layout
        // (which leaves them out) can be saved again.
        val unreadable = LayoutMapper.unreadableRows(rows)
        if (unreadable.isNotEmpty() && !unreadableKept) {
            try {
                backupStore.write(LegacyGeneration, unreadable.joinToString("\n"))
                unreadableKept = true
                logWarn("${unreadable.size} unreadable layout rows kept in a backup file")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                logWarn("unreadable layout rows could not be backed up")
            }
        }
        if (!rows.isEmpty()) {
            val applied = appliedGenerationLocked()
            val generation = applied.getOrNull()
            // A layout at the current generation (or one whose marker cannot be read) is published
            // as it stands: never reseed because of a transient store error. T62 keeps the
            // generation at 2 on purpose, so an existing install is never replaced by the seed.
            if (applied.isFailure || (generation != null && generation >= seedVersion)) {
                _layout.value = LayoutMapper.toLayout(rows, arcSpecs())
                if (_layout.value.folders.any { com.brightblu.parc.model.LockedFolderTag in it.tags }) {
                    try { store.write(LayoutMapper.toRows(_layout.value)) } catch (_: Exception) { logWarn("locked layout rewrite failed") }
                }
                ready = true
                return
            }
            // An older generation is migrated once, but only once the installed list is known: the
            // reseed has nothing to place without it, and an empty layout must never be persisted.
            if (installed.isEmpty()) {
                _layout.value = LayoutMapper.toLayout(rows, arcSpecs())
                return
            }
            migrateLocked(rows, generation ?: LegacyGeneration)
            return
        }
        if (installed.isEmpty()) return
        val seeded = seed(installed)
        persistLocked(seeded)
        recordGenerationLocked(seedVersion)
        recordSeededAtLocked()
        ready = true
    }

    /**
     * Replaces a layout from an older seed generation (T34): the old document is written to the
     * backup directory first, and only then is the current seed applied, exactly once. A backup that
     * cannot be written leaves the old layout in place and is retried on the next start, because
     * losing the previous layout is worse than running one generation behind.
     */
    private suspend fun migrateLocked(rows: LayoutRows, from: Int) {
        _layout.value = LayoutMapper.toLayout(rows, arcSpecs())
        val document = LayoutJson.encode(_layout.value)
        val name = try {
            backupStore.write(from, document)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            logWarn("layout migration backup failed")
            return
        }
        logWarn("layout migrated from v$from; previous layout saved as $name")
        val seeded = seed(installed)
        persistLocked(seeded)
        recordGenerationLocked(seedVersion)
        recordSeededAtLocked()
        ready = true
    }

    /** Writes the baseline for New once; a failed write leaves the old reconcile behaviour. */
    private suspend fun recordSeededAtLocked() {
        val store = firstRunStore ?: return
        try {
            if (store.seededAt() == null) store.setSeededAt(now())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            logWarn("first run marker write failed")
        }
    }

    private suspend fun appliedGenerationLocked(): Result<Int?> = try {
        Result.success(generationStore.applied())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        logWarn("layout generation read failed")
        Result.failure(unexpected)
    }

    private suspend fun recordGenerationLocked(generation: Int) {
        try {
            generationStore.setApplied(generation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            // The layout is already written; the marker is retried on the next start.
            logWarn("layout generation write failed")
        }
    }

    private suspend fun persistLocked(next: ParcLayout) {
        val rows = LayoutMapper.toRows(next)
        try {
            store.write(rows)
        } catch (t: Throwable) {
            // The action still lands in memory; it is simply lost on restart. Never blank the screen.
            logWarn("layout write failed")
        }
        _layout.value = LayoutMapper.toLayout(rows, arcSpecs())
    }

    companion object {
        const val LogTag = "Parc"

        /**
         * Generation a stored layout without a marker is assumed to be (T34). Every build before
         * T34 wrote such a layout, so the first start of this build migrates it.
         */
        const val LegacyGeneration = 1

        /**
         * Longest folder name, in characters (T24). The ring draws the name under a 44 dp disc as a
         * single 11 sp caption line, so a longer name would overlap its neighbours; the generated
         * seed also asserts it for every auto-built folder name.
         */
        const val MaxFolderNameLength = 8

        /** Builds the production repository for an app context. */
        fun create(context: Context, arcSpecs: () -> List<ArcSpec>): LayoutRepositoryImpl {
            val database = ParcDatabase.create(context)
            val prefs = ParcPrefs.create(context)
            val installedApps = AndroidInstalledApps.create(context)
            val suggestions = AndroidMainAppSuggestions(context)
            val seedSource = FirstRunSeedSource(
                kindOf = installedApps::kindOf,
                suggestion = suggestions::suggest,
                storedMainApps = { prefs.mainApps.first() },
            )
            val repository = LayoutRepositoryImpl(
                store = RoomLayoutStore(database.layoutDao(), RoomTransactionRunner(database), Dispatchers.IO),
                arcSpecs = arcSpecs,
                seed = { installed -> seedSource.build(installed, arcSpecs()) },
                logWarn = { Log.d(LogTag, it) },
                generationStore = PrefsLayoutGenerationStore(prefs),
                backupStore = FileLayoutBackupStore.create(context),
                mainAppsStore = PrefsMainAppsStore(prefs),
                firstRunStore = PrefsFirstRunStore(prefs),
            )
            // A picker save must reach Home without restarting Parc. The repository applies each
            // revision once; a save interrupted by a kill is picked up by the next load instead.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                prefs.mainAppsRevision.collect { repository.applyPendingMainApps() }
            }
            return repository
        }
    }
}

private fun ParcLayout.requireArc(arcId: String): ArcAssignment =
    requireNotNull(arcs.firstOrNull { it.arc.id == arcId }) { "Unknown arc: $arcId" }

/** Rejects a folder name the ring could not draw on one caption line (T24). */
private fun requireFolderName(name: String) {
    require(name.length <= LayoutRepositoryImpl.MaxFolderNameLength) {
        "Folder name longer than ${LayoutRepositoryImpl.MaxFolderNameLength} characters"
    }
}

private fun ParcLayout.slotsOf(arcId: String): Map<Int, Slot> =
    arcs.firstOrNull { it.arc.id == arcId }?.slots.orEmpty()

/** A negative index is the only invalid one (T36): a rotating ring may hold more than it shows. */
private fun requireIndex(index: Int) {
    require(index >= 0) { "Slot index is negative: $index" }
}

private fun ParcLayout.withSlot(position: SlotPosition, slot: Slot?): ParcLayout {
    requireArc(position.arcId)
    requireIndex(position.index)
    return copy(arcs = arcs.map { current ->
        if (current.arc.id != position.arcId) {
            current
        } else {
            current.copy(slots = current.slots.toMutableMap().apply {
                if (slot == null) remove(position.index) else put(position.index, slot)
            }.toMap())
        }
    })
}
