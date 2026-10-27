package com.brightblu.parc.model.fakes

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.brightblu.parc.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread fake app service. Non-null snapshots; icons always null; no Android calls. */
class FakeAppIndex(entries: List<AppEntry> = emptyList()) : AppIndex {
    private val state = MutableStateFlow(entries.toList())
    override val apps = state.asStateFlow()
    val launches = mutableListOf<AppId>()
    override fun icon(id: AppId, sizePx: Int): Bitmap? = null
    override fun launch(id: AppId) { launches.add(id) }
    override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
}

/** Thread-safe serialized in-memory layout fake. Non-null snapshots; no disk or Android calls. */
class FakeLayoutRepository(initial: ParcLayout = ParcLayout()) : LayoutRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(LayoutJson.decode(LayoutJson.encode(initial)))
    override val layout = state.asStateFlow()
    private suspend fun edit(block: (ParcLayout) -> ParcLayout) = mutex.withLock {
        state.value = block(state.value)
    }
    private fun ParcLayout.assign(position: SlotPosition, slot: Slot?): ParcLayout {
        val assignment = arcs.find { it.arc.id == position.arcId }
        requireNotNull(assignment)
        require(position.index in 0 until assignment.arc.slotCapacity)
        return copy(arcs = arcs.map {
            if (it != assignment) it else it.copy(slots = it.slots.toMutableMap().apply {
                if (slot == null) remove(position.index) else put(position.index, slot)
            }.toMap())
        })
    }
    override suspend fun pin(id: AppId, position: SlotPosition) = edit {
        it.assign(position, Slot.App(id)).copy(pins = it.pins + id)
    }
    override suspend fun unpin(id: AppId) = edit { it.copy(pins = it.pins - id) }
    override suspend fun move(from: SlotPosition, to: SlotPosition) = edit {
        val source = it.arcs.firstOrNull { a -> a.arc.id == from.arcId }
        requireNotNull(source)
        require(from.index in 0 until source.arc.slotCapacity)
        val target = it.arcs.firstOrNull { a -> a.arc.id == to.arcId }
        requireNotNull(target)
        val moved = source.slots[from.index]
        it.assign(from, target.slots[to.index]).assign(to, moved)
    }
    override suspend fun createFolder(id: FolderId, name: String) = edit {
        require(it.folders.none { folder -> folder.id == id })
        it.copy(folders = it.folders + Folder(id, name))
    }
    override suspend fun renameFolder(id: FolderId, name: String) = edit {
        require(it.folders.any { folder -> folder.id == id })
        it.copy(folders = it.folders.map { f -> if (f.id == id) f.copy(name = name) else f })
    }
    override suspend fun addToFolder(folderId: FolderId, id: AppId) = edit {
        require(it.folders.any { f -> f.id == folderId })
        it.copy(folders = it.folders.map { f ->
            if (f.id == folderId) f.copy(appIds = (f.appIds + id).distinct()) else f
        })
    }
    override suspend fun removeFromFolder(folderId: FolderId, id: AppId) = edit {
        require(it.folders.any { f -> f.id == folderId })
        it.copy(folders = it.folders.map { f ->
            if (f.id == folderId) f.copy(appIds = f.appIds - id) else f
        })
    }
    override suspend fun hide(id: AppId) = edit { it.copy(hidden = it.hidden + id) }
    override suspend fun unhide(id: AppId) = edit { it.copy(hidden = it.hidden - id) }
    override suspend fun exportLayout(): String = LayoutJson.encode(state.value)
    override suspend fun importLayout(source: String) = edit { LayoutJson.decode(source) }
}

/** Main-thread deterministic usage fake; no ranking algorithm. Non-null fixed folder ranks. */
class FakeUsageRepository(private val ranks: Map<FolderId, List<AppId>> = emptyMap()) : UsageRepository {
    private val launches = MutableStateFlow<List<AppId>>(emptyList())
    override fun recordLaunch(id: AppId) { launches.value = listOf(id) + (launches.value - id) }
    override fun recents(limit: Int): Flow<List<AppId>> {
        require(limit >= 0)
        return launches.map { it.take(limit) }
    }
    override fun rank(folderId: FolderId): List<AppId> = ranks[folderId]?.toList() ?: emptyList()
}

/** Immutable canned results fake; thread-safe if supplied list is not mutated. Non-null. */
class FakeSearchEngine(private val hits: List<SearchHit> = emptyList()) : SearchEngine {
    override fun query(text: String): List<SearchHit> = hits.toList()
}

/** Main-thread unread fake; non-null copied snapshots. */
class FakeNotificationState(initial: Map<String, Int> = emptyMap()) : NotificationState {
    private val state = MutableStateFlow(initial.toMap())
    override val unread = state.asStateFlow()
    fun setUnread(counts: Map<String, Int>) { state.value = counts.toMap() }
}

/** Main-thread skin fake; non-null immutable values. */
class FakeSkinProvider(initial: Skin = Skin.Midnight) : SkinProvider {
    private val state = MutableStateFlow(initial)
    override val active = state.asStateFlow()
    fun setSkin(skin: Skin) { state.value = skin }
}

/** No-op renderer for Compose hosts. Main-thread confined; non-null inputs. */
class FakeRingRenderer : RingRenderer {
    @Composable
    override fun Field(state: RingState, skin: Skin, callbacks: RingCallbacks) = Unit
}

/** Main-thread callback recorder; nullable hits mean no event has been received. */
class FakeRingCallbacks : RingCallbacks {
    var tap: SlotHit? = null
        private set
    var longPress: SlotHit? = null
        private set
    var drag: Pair<SlotHit, DragPhase>? = null
        private set
    var aim: SlotPosition? = null
        private set
    var aimCount = 0
        private set
    override fun onTap(hit: SlotHit) { tap = hit }
    override fun onLongPress(hit: SlotHit) { longPress = hit }
    override fun onDrag(hit: SlotHit, phase: DragPhase) { drag = hit to phase }
    override fun onAim(position: SlotPosition?) { aim = position; aimCount++ }
}
