package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The four home arcs of PROJECT.md §6.3, as the layout engine will hand them over. */
internal fun homeArcSpecs(): List<ArcSpec> = listOf(
    ArcSpec("A1", 0, 120f, 180f, 270f, 4, Layer.Home),
    ArcSpec("A2", 1, 190f, 180f, 270f, 5, Layer.Home),
    ArcSpec("A3", 2, 255f, 180f, 270f, 7, Layer.Home),
    ArcSpec("A4", 3, 315f, 202f, 270f, 7, Layer.Home),
)

/** A layout whose arcs carry no slots, which is what the mapper needs to round trip. */
internal fun emptyLayout(specs: List<ArcSpec> = homeArcSpecs()): ParcLayout =
    ParcLayout(version = LayoutVersion, arcs = specs.map { ArcAssignment(it, emptyMap()) })

internal fun appId(component: String, userSerial: Long = 0L): AppId = AppId("$component@$userSerial")

internal fun appEntry(
    id: AppId,
    label: String = id.value,
    installTime: Long = 0L,
    isSystem: Boolean = false,
): AppEntry = AppEntry(
    id = id,
    label = label,
    packageName = id.value.substringBefore('/'),
    isSystem = isSystem,
    installTime = installTime,
)

internal fun folder(id: String, name: String = id, apps: List<AppId> = emptyList()): Folder =
    Folder(FolderId(id), name, emptySet(), apps)

/**
 * In-memory [LayoutDao]. Primary keys are enforced inside one insert, so a store that forgets to
 * clear before writing fails the test instead of silently duplicating rows.
 */
internal class InMemoryLayoutDao : LayoutDao {
    private var assignmentRows = listOf<SlotAssignmentEntity>()
    private var folderRows = listOf<FolderEntity>()
    private var folderAppRows = listOf<FolderAppEntity>()
    private var metaRows = listOf<AppMetaEntity>()

    override suspend fun assignments(): List<SlotAssignmentEntity> =
        assignmentRows.sortedWith(compareBy({ it.arcId }, { it.slotIndex }))

    override suspend fun clearAssignments() {
        assignmentRows = emptyList()
    }

    override suspend fun insertAssignments(rows: List<SlotAssignmentEntity>) {
        require(rows.map { it.arcId to it.slotIndex }.distinct().size == rows.size) { "duplicate slot row" }
        assignmentRows = assignmentRows + rows
    }

    override suspend fun folders(): List<FolderEntity> =
        folderRows.sortedWith(compareBy({ it.name }, { it.id }))

    override suspend fun clearFolders() {
        folderRows = emptyList()
    }

    override suspend fun insertFolders(rows: List<FolderEntity>) {
        require(rows.map { it.id }.distinct().size == rows.size) { "duplicate folder row" }
        folderRows = folderRows + rows
    }

    override suspend fun folderApps(): List<FolderAppEntity> =
        folderAppRows.sortedWith(compareBy({ it.folderId }, { it.position }))

    override suspend fun clearFolderApps() {
        folderAppRows = emptyList()
    }

    override suspend fun insertFolderApps(rows: List<FolderAppEntity>) {
        require(rows.map { it.folderId to it.appId }.distinct().size == rows.size) { "duplicate folder_app row" }
        folderAppRows = folderAppRows + rows
    }

    override suspend fun appMeta(): List<AppMetaEntity> = metaRows

    override suspend fun clearHiddenFlags() {
        metaRows = metaRows.map { it.copy(hidden = false) }
    }

    override suspend fun insertAppMetaIfAbsent(rows: List<AppMetaEntity>) {
        val known = metaRows.mapTo(mutableSetOf()) { it.appId }
        metaRows = metaRows + rows.filter { known.add(it.appId) }
    }

    override suspend fun markHidden(ids: List<String>) {
        val wanted = ids.toSet()
        metaRows = metaRows.map { if (it.appId in wanted) it.copy(hidden = true) else it }
    }

    /** Rows as the store would read them back. */
    fun rows(): LayoutRows = LayoutRows(
        assignments = assignmentRows,
        folders = folderRows,
        folderApps = folderAppRows,
        hiddenAppIds = metaRows.filter { it.hidden }.map { it.appId },
    )

    fun metaRows(): List<AppMetaEntity> = metaRows
}

/** Runs blocks one after another without a database, the way one Room transaction would. */
internal class DirectTransactionRunner : TransactionRunner {
    private val mutex = Mutex()
    override suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

/** In-memory [LayoutGenerationStore] (T34); `null` means "no marker was ever written". */
internal class FakeGenerationStore(var marker: Int? = null, var failReads: Boolean = false) : LayoutGenerationStore {
    var writes = 0
    override suspend fun applied(): Int? {
        if (failReads) throw IllegalStateException("generation unavailable")
        return marker
    }

    override suspend fun setApplied(generation: Int) {
        marker = generation
        writes++
    }
}

/** Records migration backups instead of writing files (T34). */
internal class RecordingBackupStore(private val failure: Boolean = false) : LayoutBackupStore {
    val written = mutableListOf<Pair<Int, String>>()
    var attempts = 0
    override suspend fun write(generation: Int, document: String): String {
        attempts++
        if (failure) throw IllegalStateException("backup unavailable")
        written += generation to document
        return "layout-v$generation-test.json"
    }
}

/** A repository plus the rows behind it; make models with [newRepository] to fake a restart. */
internal class RepositoryHarness(
    private val seed: ParcLayout? = null,
    private val specs: List<ArcSpec> = homeArcSpecs(),
    private val store: LayoutStore? = null,
    val generationStore: FakeGenerationStore = FakeGenerationStore(),
    val backupStore: RecordingBackupStore = RecordingBackupStore(),
    private val seedVersion: Int = SeedGeneration.Current,
    val mainAppsStore: FakeMainAppsStore? = null,
    val firstRunStore: FirstRunStore? = null,
    private val now: Long = 1_700_000_000_000L,
) {
    val dao = InMemoryLayoutDao()
    val warnings = mutableListOf<String>()
    private val layoutStore = store ?: RoomLayoutStore(dao, DirectTransactionRunner(), Dispatchers.Unconfined)

    val repository: LayoutRepositoryImpl = newRepository()

    /**
     * A second repository over the same rows: what a process kill and cold start would see. A
     * different [seedVersion] makes the stored layout belong to an older generation (T34).
     */
    fun newRepository(seedVersion: Int = this.seedVersion): LayoutRepositoryImpl = LayoutRepositoryImpl(
        store = layoutStore,
        arcSpecs = { specs },
        seed = { seed ?: emptyLayout(specs) },
        logWarn = { warnings += it },
        generationStore = generationStore,
        backupStore = backupStore,
        seedVersion = seedVersion,
        mainAppsStore = mainAppsStore,
        firstRunStore = firstRunStore,
        now = { now },
    )
}

/** In-memory [FirstRunStore] for the T62 baseline tests. */
internal class FakeFirstRunStore(var value: Long? = null) : FirstRunStore {
    override suspend fun seededAt(): Long? = value

    override suspend fun setSeededAt(millis: Long) {
        value = millis
    }
}

/** In-memory [MainAppsStore] for the T62 picker tests. */
internal class FakeMainAppsStore(
    var revision: Long = 0L,
    var applied: Long = 0L,
    var ids: List<AppId> = emptyList(),
) : MainAppsStore {
    override suspend fun appliedRevision(): Long = applied

    override suspend fun pending(applied: Long): MainAppsUpdate? =
        if (revision <= applied) null else MainAppsUpdate(revision, ids)

    override suspend fun markApplied(revision: Long) {
        applied = revision
    }
}
