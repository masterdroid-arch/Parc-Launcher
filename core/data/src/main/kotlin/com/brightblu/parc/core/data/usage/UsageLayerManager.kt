package com.brightblu.parc.core.data.usage

import android.content.Context
import android.util.Log
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.SlotPosition
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One change the usage layer made (T34): [position] now holds [to] instead of [from]. [from] is null
 * when the slot was empty, which is a fill rather than a swap. Immutable, non-null.
 */
data class UsageSwap(val position: SlotPosition, val from: AppId?, val to: AppId)

/**
 * Learned usage layer (T34): the A2 slots that hold the apps used most, instead of apps chosen
 * once by hand.
 *
 * The layer is four learned slots by default (the `usageSlots` DataStore value lists them, so the
 * geometry is data, not code). At cold start the manager asks whether a re-rank is due - at most one
 * per local 04:00 day, which also covers the first start of a new build - and if so it replaces
 * incumbents that a candidate beats by more than 25 % (see [UsageLayerRanking]), writing the winners
 * through the frozen [LayoutRepository]. Nothing happens mid-session: a launch never moves a slot.
 *
 * A hard-pinned app never moves and a hidden app never enters. Changes are published on
 * [lastChanges] so the ring can pulse the newcomer once ([consumeChanges] acknowledges them).
 *
 * Threading: [refresh] does its work on the caller's dispatcher, which must not be the main thread
 * (it awaits the launch log and writes DataStore). [lastChanges] is safe to collect anywhere.
 */
class UsageLayerManager internal constructor(
    private val repository: LayoutRepository,
    private val scores: LaunchScoreSource,
    private val installed: () -> List<AppId>,
    private val slotStore: UsageSlotStore,
    private val clock: () -> Long,
    private val zoneOffsetMillis: (Long) -> Int,
    private val logWarn: (String) -> Unit,
) {

    private val _lastChanges = MutableStateFlow<List<UsageSwap>>(emptyList())

    /** Changes of the most recent re-rank, newest run replacing the previous list. */
    val lastChanges: StateFlow<List<UsageSwap>> = _lastChanges.asStateFlow()

    /**
     * One cold-start pass. Returns without doing anything when a re-rank has already run on this
     * 04:00 day, which is what keeps the layer still for the rest of the day.
     */
    suspend fun refresh() {
        removeLockedResidents()
        val now = clock()
        val dayKey = UsageLayerRanking.dayKey(now, zoneOffsetMillis(now))
        val state = readState() ?: return
        if (!UsageLayerRanking.isDue(state.lastRankDay, dayKey)) return
        val layout = repository.layout.value
        val positions = UsageLayerRanking.positionsIn(layout, state.slots)
        // No arc to hold the layer on this geometry: leave the day marker unset and try next start.
        if (positions.isEmpty()) return
        guarded("usage layer scores failed") { scores.awaitLoaded() }
        val plan = UsageLayerRanking.plan(
            positions = positions,
            layout = layout,
            installed = installed().toHashSet(),
            scores = scores.scores(now),
        )
        val applied = apply(plan)
        guarded("usage layer slot write failed") {
            slotStore.write(state.copy(slots = positions, lastRankDay = dayKey))
        }
        _lastChanges.value = applied
    }

    /** Lock changes repair learned residents even when today's daily ranking has already run. */
    suspend fun removeLockedResidents() {
        val state = readState() ?: return
        val current = repository.layout.value
        val repaired = UsageLayerRanking.repairLockedSlots(current,
            UsageLayerRanking.positionsIn(current, state.slots), installed().toSet(), scores.scores(clock()))
        if (repaired != current) guarded("locked usage repair failed") {
            repository.importLayout(com.brightblu.parc.model.LayoutJson.encode(repaired))
        }
    }

    /** Acknowledges [lastChanges]; the ring calls this once it has played the highlight. */
    fun consumeChanges() {
        _lastChanges.value = emptyList()
    }

    private suspend fun readState(): UsageSlotState? = try {
        slotStore.read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        logWarn("usage layer read failed")
        null
    }

    /**
     * Writes one winner per changed slot and reports which changes actually landed. `pin` both fills
     * the slot and flags it, and a learned slot must not be locked, so the flag is cleared straight
     * away; the slot keeps its app either way (the contract's `unpin` only clears the flag). A failed
     * pair costs that change, never a crash.
     */
    private suspend fun apply(changes: List<UsageSwap>): List<UsageSwap> {
        val applied = ArrayList<UsageSwap>(changes.size)
        for (change in changes) {
            try {
                repository.pin(change.to, change.position)
                repository.unpin(change.to)
                applied += change
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                logWarn("usage layer write failed")
            }
        }
        return applied
    }

    private inline fun <T> guarded(message: String, read: () -> T): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        logWarn(message)
        null
    }

    companion object {
        const val LogTag = "Parc"

        /**
         * Builds the production manager. [refresh] is a cold-start step: call it from a background
         * coroutine once the layout is loaded and before the ring's first frame, and again never in
         * the same process. No contract change: the layer writes through [LayoutRepository].
         */
        fun create(
            context: Context,
            repository: LayoutRepository,
            scores: LaunchScoreSource,
            installed: () -> List<AppId>,
        ): UsageLayerManager = UsageLayerManager(
            repository = repository,
            scores = scores,
            installed = installed,
            slotStore = PrefsUsageSlotStore(ParcPrefs.create(context)),
            clock = System::currentTimeMillis,
            zoneOffsetMillis = { TimeZone.getDefault().getOffset(it) },
            logWarn = { Log.d(LogTag, it) },
        )
    }
}
