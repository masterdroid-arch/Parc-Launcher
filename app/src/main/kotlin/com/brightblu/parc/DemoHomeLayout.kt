package com.brightblu.parc

import com.brightblu.parc.core.data.LayoutRepositoryImpl
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T122: the arc home store used by the app. The real implementation delegates to :core:data;
 * demo mode swaps in a store that never constructs, reads or writes the real layout store.
 */
interface LayoutStore : LayoutRepository {
    /** OPUS6: the stored layout was read successfully; nothing derived from it is saved before. */
    val loaded: Boolean
    suspend fun load(installed: List<AppEntry>)
    suspend fun syncInstalled(installed: List<AppEntry>)
    suspend fun updateLayout(change: (ParcLayout) -> ParcLayout)
}

/** Production store. All persistence stays in the existing :core:data repository. */
internal class RealLayoutStore private constructor(
    private val delegate: LayoutRepositoryImpl,
) : LayoutStore, LayoutRepository by delegate {

    override val loaded: Boolean get() = delegate.loaded

    override suspend fun load(installed: List<AppEntry>) = delegate.load(installed)

    override suspend fun syncInstalled(installed: List<AppEntry>) = delegate.syncInstalled(installed)

    override suspend fun updateLayout(change: (ParcLayout) -> ParcLayout) = delegate.updateLayout(change)

    companion object {
        fun create(context: android.content.Context, arcSpecs: () -> List<ArcSpec>): RealLayoutStore =
            RealLayoutStore(LayoutRepositoryImpl.create(context, arcSpecs))
    }
}

/**
 * Demo mode's only layout source. It is created from the hard-coded sample below and has no
 * reference to the real store, so every read and every edit stays in this process.
 */
internal class DemoLayoutStore(initial: ParcLayout) : LayoutStore {

    private val _layout = MutableStateFlow(initial)
    override val layout: StateFlow<ParcLayout> = _layout.asStateFlow()

    override val loaded: Boolean get() = true

    override suspend fun load(installed: List<AppEntry>) = Unit

    override suspend fun syncInstalled(installed: List<AppEntry>) = Unit

    override suspend fun updateLayout(change: (ParcLayout) -> ParcLayout) {
        _layout.value = change(_layout.value)
    }

    override suspend fun pin(id: AppId, position: SlotPosition) {
        requireIndex(position.index)
        val current = _layout.value
        val arc = requireArc(current, position.arcId)
        _layout.value = current.copy(
            arcs = current.arcs.map {
                if (it.arc.id != arc.arc.id) it
                else it.copy(slots = it.slots.toMutableMap().apply { put(position.index, Slot.App(id)) })
            },
            pins = current.pins + id,
        )
    }

    override suspend fun unpin(id: AppId) {
        val current = _layout.value
        _layout.value = current.copy(pins = current.pins - id)
    }

    override suspend fun move(from: SlotPosition, to: SlotPosition) {
        requireIndex(from.index)
        requireIndex(to.index)
        val current = _layout.value
        val fromArc = requireArc(current, from.arcId)
        val toArc = requireArc(current, to.arcId)
        val moving = fromArc.slots[from.index]
        val replaced = toArc.slots[to.index]
        _layout.value = current
            .withSlot(from, replaced)
            .withSlot(to, moving)
    }

    override suspend fun createFolder(id: FolderId, name: String) {
        requireFolderName(name)
        val current = _layout.value
        require(current.folders.none { it.id == id }) { "Folder already exists: ${id.value}" }
        _layout.value = current.copy(folders = current.folders + Folder(id = id, name = name))
    }

    override suspend fun renameFolder(id: FolderId, name: String) {
        requireFolderName(name)
        val current = _layout.value
        require(current.folders.any { it.id == id }) { "Unknown folder: ${id.value}" }
        _layout.value = current.copy(folders = current.folders.map {
            if (it.id == id) it.copy(name = name) else it
        })
    }

    override suspend fun addToFolder(folderId: FolderId, id: AppId) {
        val current = _layout.value
        require(current.folders.any { it.id == folderId }) { "Unknown folder: ${folderId.value}" }
        _layout.value = current.copy(folders = current.folders.map {
            if (it.id == folderId) it.copy(appIds = (it.appIds + id).distinct()) else it
        })
    }

    override suspend fun removeFromFolder(folderId: FolderId, id: AppId) {
        val current = _layout.value
        require(current.folders.any { it.id == folderId }) { "Unknown folder: ${folderId.value}" }
        _layout.value = current.copy(folders = current.folders.map {
            if (it.id == folderId) it.copy(appIds = it.appIds - id) else it
        })
    }

    override suspend fun hide(id: AppId) {
        val current = _layout.value
        _layout.value = current.copy(hidden = current.hidden + id)
    }

    override suspend fun unhide(id: AppId) {
        val current = _layout.value
        _layout.value = current.copy(hidden = current.hidden - id)
    }

    override suspend fun exportLayout(): String = LayoutJson.encode(_layout.value)

    override suspend fun importLayout(source: String) {
        _layout.value = LayoutJson.decode(source)
    }
}

/**
 * T122 demo fixture. One hard-coded list of apps and one hard-coded list of home placements
 * (12 pinned apps plus four folders). Nothing here is read from the device, a contact, or the
 * author's real app list, and the sample is never written into the real layout store.
 */
internal object DemoHomeLayout {

    /** How many pinned apps the sample shows. */
    const val PinnedAppCount = 12

    /** Folder names in the sample, in home order. */
    val FolderNames = listOf("Social", "Tools", "Media", "Games")

    private data class SampleApp(val key: String, val label: String)

    /** The only app list in the sample. Labels are intentionally generic. */
    private val sampleApps = listOf(
        SampleApp("notes", "Notes"),
        SampleApp("maps", "Maps"),
        SampleApp("music", "Music"),
        SampleApp("camera", "Camera"),
        SampleApp("clock", "Clock"),
        SampleApp("weather", "Weather"),
        SampleApp("photos", "Photos"),
        SampleApp("files", "Files"),
        SampleApp("calendar", "Calendar"),
        SampleApp("calculator", "Calculator"),
        SampleApp("recorder", "Recorder"),
        SampleApp("wallet", "Wallet"),
        SampleApp("chat", "Chat"),
        SampleApp("mail", "Mail"),
        SampleApp("feed", "Feed"),
        SampleApp("calls", "Calls"),
        SampleApp("compass", "Compass"),
        SampleApp("translator", "Translator"),
        SampleApp("scanner", "Scanner"),
        SampleApp("timer", "Timer"),
        SampleApp("podcasts", "Podcasts"),
        SampleApp("video", "Video"),
        SampleApp("radio", "Radio"),
        SampleApp("gallery", "Gallery"),
        SampleApp("solitaire", "Solitaire"),
        SampleApp("blocks", "Blocks"),
        SampleApp("sudoku", "Sudoku"),
    )

    private val apps: List<AppEntry> = sampleApps.map {
        AppEntry(
            id = AppId("demo/${it.key}"),
            label = it.label,
            packageName = "parc.demo.${it.key}",
            isSystem = false,
            installTime = 0L,
        )
    }

    private val byKey: Map<String, AppId> = sampleApps.zip(apps) { sample, entry ->
        sample.key to entry.id
    }.toMap()

    private val folders = listOf(
        Folder(FolderId("social"), "Social", appIds = ids("chat", "mail", "feed", "calls")),
        Folder(FolderId("tools"), "Tools", appIds = ids("compass", "translator", "scanner", "timer")),
        Folder(FolderId("media"), "Media", appIds = ids("podcasts", "video", "radio", "gallery")),
        Folder(FolderId("games"), "Games", appIds = ids("solitaire", "blocks", "sudoku")),
    )

    private data class Placement(val arcId: String, val index: Int, val slot: Slot)

    /** The one home placement list: 12 app slots on A1-A3, then the four folders on A3. */
    private val placements = listOf(
        Placement("A1", 0, app("notes")),
        Placement("A1", 1, app("maps")),
        Placement("A1", 2, app("music")),
        Placement("A1", 3, app("camera")),
        Placement("A2", 0, app("clock")),
        Placement("A2", 1, app("weather")),
        Placement("A2", 2, app("photos")),
        Placement("A2", 3, app("files")),
        Placement("A2", 4, app("calendar")),
        Placement("A3", 0, app("calculator")),
        Placement("A3", 1, app("recorder")),
        Placement("A3", 2, app("wallet")),
        Placement("A3", 3, Slot.Folder(FolderId("social"))),
        Placement("A3", 4, Slot.Folder(FolderId("tools"))),
        Placement("A3", 5, Slot.Folder(FolderId("media"))),
        Placement("A3", 6, Slot.Folder(FolderId("games"))),
    )

    /** Placeholder entries; only returned when [demo] is true. */
    fun appsIfDemo(demo: Boolean): List<AppEntry> = if (demo) apps else emptyList()

    /** T130: the label for a sample app, used by the demo icon painter. Null for anything else. */
    fun labelFor(id: AppId): String? = apps.firstOrNull { it.id == id }?.label

    /** True for the sample's app ids, so the home never asks the real icon cache for them. */
    fun isSampleApp(id: AppId): Boolean = id.value.startsWith("demo/")

    /** The sample layout; null when demo is off. */
    fun layoutIfDemo(demo: Boolean, arcs: List<ArcSpec>): ParcLayout? =
        if (demo) sample(arcs) else null

    /** The sample with the current geometry's arc ids (slot indices are the hard-coded list). */
    fun sample(arcs: List<ArcSpec>): ParcLayout {
        val assignments = arcs.map { arc ->
            ArcAssignment(arc, placements.filter { it.arcId == arc.id }.associate { it.index to it.slot })
        }
        val pins = placements.mapNotNull { (it.slot as? Slot.App)?.id }.toSet()
        return ParcLayout(
            version = 1,
            arcs = assignments,
            folders = folders,
            pins = pins,
            hidden = emptySet(),
        )
    }

    /**
     * The wiring point. Demo on returns an in-memory store and never calls [realStore]; demo off
     * returns exactly the real store the app built before this task.
     */
    fun select(demo: Boolean, arcs: List<ArcSpec>, realStore: () -> LayoutStore): LayoutStore =
        if (demo) DemoLayoutStore(sample(arcs)) else realStore()

    private fun ids(vararg keys: String): List<AppId> = keys.map { key ->
        requireNotNull(byKey[key]) { "Unknown demo app: $key" }
    }

    private fun app(key: String): Slot = Slot.App(requireNotNull(byKey[key]) { "Unknown demo app: $key" })
}

private fun requireIndex(index: Int) {
    require(index >= 0) { "Slot index is negative: $index" }
}

private fun requireFolderName(name: String) {
    require(name.length <= LayoutRepositoryImpl.MaxFolderNameLength) {
        "Folder name longer than ${LayoutRepositoryImpl.MaxFolderNameLength} characters"
    }
}

private fun requireArc(layout: ParcLayout, arcId: String): ArcAssignment =
    requireNotNull(layout.arcs.firstOrNull { it.arc.id == arcId }) { "Unknown arc: $arcId" }

private fun ParcLayout.withSlot(position: SlotPosition, slot: Slot?): ParcLayout =
    copy(arcs = arcs.map { current ->
        if (current.arc.id != position.arcId) current
        else current.copy(slots = current.slots.toMutableMap().apply {
            if (slot == null) remove(position.index) else put(position.index, slot)
        })
    })
