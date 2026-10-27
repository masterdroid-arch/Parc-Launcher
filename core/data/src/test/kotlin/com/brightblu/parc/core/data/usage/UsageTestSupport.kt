package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.homeArcSpecs
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition

/** A component-shaped test id; the package part is what the enrichment resolver reads. */
internal fun testId(name: String, user: Long = 0L): AppId =
    AppId("com.test.$name/com.test.$name.Main@$user")

/** A folder whose membership order is the order [members] is given in. */
internal fun testFolder(name: String, members: List<AppId>): Folder =
    Folder(FolderId(name.lowercase()), name, emptySet(), members)

/** In-memory launch log; `load` sorts by ts so a test never depends on insertion order. */
internal class InMemoryUsageStore : UsageStore {

    private val rows = ArrayList<LaunchRow>()

    var prunedTo: Int? = null
        private set

    /** Newest first, like the DAO's `ts DESC, id DESC`: equal timestamps keep insertion order. */
    override suspend fun load(limit: Int): List<LaunchRow> =
        rows.asReversed().sortedByDescending { it.ts }.take(limit)

    override suspend fun record(row: LaunchRow) {
        rows += row
    }

    override suspend fun prune(keep: Int) {
        prunedTo = keep
        if (rows.size > keep) {
            val newest = rows.asReversed().sortedByDescending { it.ts }.take(keep)
            rows.clear()
            rows += newest
        }
    }

    fun all(): List<LaunchRow> = rows.toList()

    fun countFor(id: AppId): Int = rows.count { it.appId == id }
}

/** In-memory rank state; [writes] counts persisted documents the way a restart would see them. */
internal class InMemoryUsageStateStore(initial: UsageRankState = UsageRankState()) : UsageStateStore {

    var state: UsageRankState = initial
        private set

    var writes: Int = 0
        private set

    override fun read(): UsageRankState = state

    override fun write(state: UsageRankState) {
        this.state = state
        writes++
    }
}

/** Enrichment fake: [events] null means "special access not granted". */
internal class FakeEnrichment(var events: List<UsageEvent>? = null) : UsageStatsEnrichment {

    var calls: Int = 0
        private set

    var lastWindow: Pair<Long, Long>? = null
        private set

    override fun foregroundEvents(sinceMillis: Long, untilMillis: Long): List<UsageEvent>? {
        calls++
        lastWindow = sinceMillis to untilMillis
        return events
    }
}

/** A home layout carrying the slots a test asks for (T34); arcs come from [homeArcSpecs]. */
internal fun usageLayerLayout(
    slots: Map<SlotPosition, Slot> = emptyMap(),
    pins: Set<AppId> = emptySet(),
    hidden: Set<AppId> = emptySet(),
): ParcLayout = ParcLayout(
    version = 1,
    arcs = homeArcSpecs().map { spec ->
        ArcAssignment(spec, slots.filterKeys { it.arcId == spec.id }.mapKeys { it.key.index })
    },
    pins = pins,
    hidden = hidden,
)

/** Four learned A2 slots plus one hard-pinned app at slot 4. */
internal fun seededUsageLayout(
    learned: List<AppId>,
    pinned: AppId,
    pins: Set<AppId> = setOf(pinned),
    hidden: Set<AppId> = emptySet(),
): ParcLayout = usageLayerLayout(
    slots = learned.mapIndexed { index, id -> SlotPosition("A2", index) to Slot.App(id) }.toMap() +
        (SlotPosition("A2", 4) to Slot.App(pinned)),
    pins = pins,
    hidden = hidden,
)

/** Scores fixed by the test, with an [awaited] flag so the cold-start wait is observable. */
internal class FakeScores(private var values: Map<AppId, Double> = emptyMap()) : LaunchScoreSource {
    var awaited: Boolean = false
        private set

    fun set(newValues: Map<AppId, Double>) {
        values = newValues
    }

    override fun scores(nowMillis: Long): Map<AppId, Double> = values

    override suspend fun awaitLoaded() {
        awaited = true
    }
}

/** In-memory usage-slot state; [writes] counts persisted documents. */
internal class FakeSlotStore(initial: UsageSlotState = UsageSlotState()) : UsageSlotStore {
    var state: UsageSlotState = initial
        private set

    var writes: Int = 0
        private set

    override suspend fun read(): UsageSlotState = state

    override suspend fun write(state: UsageSlotState) {
        this.state = state
        writes++
    }
}
