package com.brightblu.parc.core.data.focus

import android.content.Context
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/**
 * Focus modes and their schedules (T72): the stored modes, the manual override, and a [state] stream
 * that always says which mode applies *now*.
 *
 * The timer is a coroutine on the scope passed to [create] (the app process scope), not an alarm and
 * not a service. It sleeps exactly until the next scheduled boundary, then resolves again; editing
 * the modes or the override cancels that sleep and re-resolves immediately. When no boundary exists
 * the loop stops, so an idle repository schedules no wakeup at all. There is no exact-alarm
 * permission, no foreground service and no manifest entry.
 *
 * Clock: [clock] is epoch milliseconds and is read only when resolving, so a manual override is
 * always compared against the real current time. A wall-clock or time-zone change that happens
 * *during* a long sleep is not signalled (there is no receiver): the next [state] is correct as soon
 * as the sleep ends, on an edit, or on the next process start.
 *
 * Threading: [state] and [modes] are [StateFlow]s and safe to collect anywhere. The suspend methods
 * may be called from any dispatcher; DataStore does its own IO. The same repository instance must be
 * used process-wide so there is only one timer.
 */
class FocusRepository internal constructor(
    private val store: FocusModeStore,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val elapsed: () -> Long = clock,
    private val paidUnlock: PaidUnlock? = null,
) {

    private val _state = MutableStateFlow(FocusState())
    private val _summary = MutableStateFlow<FocusSummary?>(null)
    val summary: StateFlow<FocusSummary?> = _summary.asStateFlow()
    private var sessionName: String? = null
    private var focusedMillis = 0L
    private var lastTick = 0L
    private var attempts = 0
    private val exitMutex = Mutex()
    @Volatile private var request: FocusExitRequest? = null
    private val sessionPasses = mutableListOf<Long>()

    fun cancelExit(ticket: FocusExitRequest) { if (request === ticket) request = null }
    fun exitRemaining(ticket: FocusExitRequest): Long = (ticket.readyAt - elapsed()).coerceAtLeast(0)
    fun hold(ticket: FocusExitRequest, pressed: Boolean) {
        if (request === ticket && exitRemaining(ticket) == 0L) ticket.holdStarted = if (pressed) elapsed() else null
    }
    fun holdRemaining(ticket: FocusExitRequest): Long = ticket.holdStarted?.let {
        (com.brightblu.parc.model.Metrics.focusHoldMs - (elapsed() - it)).coerceAtLeast(0)
    } ?: com.brightblu.parc.model.Metrics.focusHoldMs

    val paidUnlockAvailable: Boolean get() = paidUnlock != null

    suspend fun beginExit(exit: FocusExit, paid: Boolean = false): FocusExitRequest? = exitMutex.withLock {
        request = null
        val mode = state.value.active ?: return@withLock null
        val current = store.modes.first().firstOrNull { it.name == mode.name } ?: return@withLock null
        val now = clock()
        if (exit == FocusExit.Pass && !current.friction.unlimitedPasses && now < FocusFrictionRules.cooldownUntil(current)) return@withLock null
        if (paid && paidUnlock == null) return@withLock null
        if (FocusFrictionRules.limitReached(current, now, zone()) && current.friction.accountabilitySecret == null && !paid) return@withLock null
        FocusExitRequest(current.name, exit, elapsed() + FocusFrictionRules.delayMillis(current, now, zone()),
            if (paid) FocusChallenge.None else FocusFrictionRules.challenge(current, now, zone()), paid = paid).also { request = it }
    }

    suspend fun completeExit(ticket: FocusExitRequest, input: String = "", answers: List<String> = emptyList()): Boolean = exitMutex.withLock {
        if (request !== ticket || state.value.activeName != ticket.modeName || exitRemaining(ticket) != 0L) return@withLock false
        val mode = store.modes.first().firstOrNull { it.name == ticket.modeName } ?: return@withLock false
        val now = clock()
        if (ticket.exit == FocusExit.Pass && !mode.friction.unlimitedPasses && now < FocusFrictionRules.cooldownUntil(mode)) return@withLock false
        val required = if (ticket.paid) FocusChallenge.None else FocusFrictionRules.challenge(mode, now, zone())
        if (required != ticket.challenge) return@withLock false
        val valid = when (required) {
            FocusChallenge.None -> true
            FocusChallenge.Hold -> holdRemaining(ticket) == 0L
            FocusChallenge.Sentence -> input == ticket.sentence
            FocusChallenge.Arithmetic -> answers.size == 5 && ticket.problems.zip(answers).all { (p, a) -> a.toIntOrNull() == p.answer }
            FocusChallenge.Accountability -> FocusTotp.valid(mode.friction.accountabilitySecret, input, now)
        }
        if (!valid) return@withLock false
        if (ticket.paid && paidUnlock?.authorize(mode.name) != true) return@withLock false
        if (request !== ticket || state.value.activeName != ticket.modeName) return@withLock false
        val current = store.override.first()
        val nextOverride = if (ticket.exit == FocusExit.Pass) {
            val until = now + mode.friction.passMinutes * 60_000L
            if (current?.on == true && current.modeName == mode.name) current.copy(breakUntilMillis = until, emergencyPasses = current.emergencyPasses + now)
            else FocusOverride(mode.name, true, state.value.untilMillis, until, sessionPasses.toList() + now)
        } else FocusRules.manualOffUntil(now, mode, zone())?.let { FocusOverride(mode.name, false, it) }
        val accepted = store.grantExit(mode.name, FocusUse(now, ticket.exit == FocusExit.Pass), nextOverride,
            FocusFrictionRules.periodStart(now, PassPeriod.Week, zone())) {
            request === ticket && state.value.activeName == ticket.modeName
        }
        request = null
        accepted
    }

    fun recordBlockedAttempt() { attempts++ }
    suspend fun refresh() { tickOnceRefresh.value++ }
    private val tickOnceRefresh = MutableStateFlow(0)
    fun consumeSummary() { _summary.value = null }

    private suspend fun protectedMode(): FocusMode? {
        state.value.active?.takeIf { it.strict }?.let { return it }
        val current = store.override.first() ?: return null
        if (!current.on || !current.isLiveAt(clock())) return null
        return store.modes.first().firstOrNull { it.name == current.modeName && it.strict }
    }

    suspend fun quickStart(name: String, minutes: Int? = 60) {
        val mode = store.modes.first().firstOrNull { it.name == name && it.enabled } ?: return
        if (protectedMode() != null) return
        val now = clock()
        val until = if (minutes == null) java.time.Instant.ofEpochMilli(now).atZone(zone())
            .toLocalDate().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
            else now + minutes.coerceIn(1, 24 * 60) * 60_000L
        store.writeOverride(FocusOverride(mode.name, true, until))
    }

    /** Legacy entry points cannot grant an exit; use beginExit / completeExit. */
    suspend fun breakPass(phrase: String = ""): Boolean = false
    suspend fun setManualOff(name: String, phrase: String = ""): Boolean = false

    /** Which mode applies now, whether it is manual, and when that can change. */
    val state: StateFlow<FocusState> = _state.asStateFlow()

    /**
     * OPUS6 (audit F4): saved modes this build cannot read and keeps untouched; -1 when the whole
     * saved list is unreadable (then edits are refused rather than written over it).
     */
    val unreadableModes: StateFlow<Int> =
        store.unreadable.catch { emit(0) }.stateIn(scope, SharingStarted.Eagerly, 0)

    /** The stored modes in resolution order (first match wins). Empty until DataStore has loaded. */
    val modes: StateFlow<List<FocusMode>> =
        store.modes.catch { /* Keep the last published modes on a storage failure. */ }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            combine(store.modes, store.override, tickOnceRefresh) { modes, override, _ -> modes to override }
                .catch { /* A storage failure must not escape the process scope. */ }
                .collectLatest { (modes, override) ->
                    try { tick(modes, override) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the last coherent state; next refresh retries. */ }
                }
        }
    }

    /**
     * Adds [mode], or replaces the stored mode with the same name in the same list position.
     * Surrounding whitespace is trimmed from the name and blank package names are dropped. Renaming
     * passes [originalName], replacing that row without deleting its saved options.
     */
    suspend fun save(mode: FocusMode, originalName: String = mode.name): Boolean {
        if (protectedMode() != null) return false
        val clean = mode.copy(
            name = mode.name.trim(),
            hiddenPackages = mode.hiddenPackages
                .mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }
                .toSet(),
        )
        var saved = false
        store.updateModes { current ->
            if (current.any { it.name == clean.name && it.name != originalName }) return@updateModes current
            saved = true
            val index = current.indexOfFirst { it.name == originalName }
            if (index < 0) {
                current + clean
            } else {
                current.toMutableList().also { it[index] = clean.copy(ledger = current[index].ledger) }
            }
        }
        if (saved && originalName != clean.name) {
            store.override.first()?.takeIf { it.modeName == originalName }?.let {
                store.writeOverride(it.copy(modeName = clean.name))
            }
        }
        return saved
    }

    /** Undo a deletion at its original position without overwriting a newly created namesake. */
    suspend fun restoreDeleted(mode: FocusMode, position: Int) {
        if (protectedMode() != null) return
        store.updateModes { modes ->
            if (modes.any { it.name == mode.name }) modes
            else modes.toMutableList().also { it.add(position.coerceIn(0, it.size), mode) }
        }
    }

    /** Explicit reorder adopts list precedence atomically; untouched legacy modes retain their ordering. */
    suspend fun move(name: String, delta: Int) {
        if (protectedMode() != null) return
        store.updateModes { modes ->
            val ordered = modes.sortedByDescending { it.priority }.toMutableList()
            val from = ordered.indexOfFirst { it.name == name }
            val to = from + delta
            if (from < 0 || to !in ordered.indices) modes else {
                ordered.add(to, ordered.removeAt(from))
                ordered.map { it.copy(priority = 0) }
            }
        }
    }

    /** Removes the mode with [name] and any manual override that referred to it. */
    suspend fun delete(name: String) {
        if (protectedMode() != null) return
        store.updateModes { current -> current.filterNot { it.name == name } }
        if (store.override.first()?.modeName == name) store.writeOverride(null)
    }

    /**
     * Forces [name] on now, ahead of its schedule. It lasts until the schedule's next closing time
     * (see [FocusRules.manualOnUntil]), or until turned off when the schedule is disabled or has no
     * days. An unknown name does nothing.
     */
    suspend fun setManualOn(name: String) {
        val mode = store.modes.first().firstOrNull { it.name == name && it.enabled } ?: return
        if (protectedMode() != null) return
        val now = clock()
        store.writeOverride(
            FocusOverride(
                modeName = name,
                on = true,
                untilMillis = FocusRules.manualOnUntil(now, mode, zone()),
            ),
        )
    }

    /** Clears an inactive override. Active exits must use the friction gate. */
    suspend fun clearManualOverride() {
        if (state.value.active == null && protectedMode() == null) store.writeOverride(null)
    }

    /**
     * One resolve-and-sleep loop. [collectLatest] restarts it whenever the modes or the override
     * change, so an edit is visible immediately and the old sleep is cancelled.
     */
    private suspend fun tick(modes: List<FocusMode>, override: FocusOverride?) {
        while (true) {
            val now = clock()
            val resolution = FocusRules.resolve(now, modes, override, zone())
            if (lastTick != 0L && _state.value.active != null) focusedMillis += (now - lastTick).coerceAtLeast(0L)
            val paused = override?.breakUntilMillis?.let { now < it } == true && override.isLiveAt(now)
            val name = resolution.mode?.name ?: if (paused) override?.modeName else null
            if (name != sessionName) {
                sessionName?.let { _summary.value = FocusSummary(it, focusedMillis, attempts, sessionPasses.toList()) }
                sessionName = name; focusedMillis = 0L; attempts = 0; sessionPasses.clear()
            }
            if (override?.on == true && override.modeName == name) {
                sessionPasses.clear(); sessionPasses.addAll(override.emergencyPasses)
            }
            lastTick = now
            _state.value = FocusState(
                active = resolution.mode,
                manual = resolution.manual,
                nextChangeMillis = resolution.nextChangeMillis,
                untilMillis = if (resolution.manual) override?.untilMillis else resolution.mode?.let { FocusRules.currentWindowEnd(now, it, zone()) },
                breakUntilMillis = override?.breakUntilMillis?.takeIf { it > now && override.isLiveAt(now) },
                evaluatedAtMillis = if (resolution.mode != null || paused) now else 0L,
                strictSession = resolution.mode?.strict == true || (paused && modes.firstOrNull { it.name == override?.modeName }?.strict == true),
            )
            val next = resolution.nextChangeMillis
            if (next == null && resolution.mode == null) return
            delay(if (next == null) com.brightblu.parc.model.Metrics.focusRefreshMs else (next - now).coerceIn(1L, com.brightblu.parc.model.Metrics.focusRefreshMs))
        }
    }

    companion object {
        /**
         * OPUS6 (audit F8): the stored modes and manual override, read without a repository, for
         * the Do Not Disturb receiver that runs while Parc's process may be dead.
         */
        suspend fun readStored(context: Context): Pair<List<FocusMode>, FocusOverride?> {
            val store = FocusPrefs.create(context)
            return store.modes.first() to store.override.first()
        }

        /**
         * Production factory. Pass the application scope: the ticker must outlive the screen and
         * stop only when the process does. Reads and writes go to the `parc_focus` DataStore.
         */
        fun create(context: Context, scope: CoroutineScope, paidUnlock: PaidUnlock? = null): FocusRepository = FocusRepository(
            store = FocusPrefs.create(context),
            scope = scope,
            clock = System::currentTimeMillis,
            zone = { ZoneId.systemDefault() },
            elapsed = android.os.SystemClock::elapsedRealtime,
            paidUnlock = paidUnlock,
        )
    }
}
