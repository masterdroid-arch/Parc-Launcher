package com.brightblu.parc.core.data

import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.core.data.profiles.toProfileSet
import com.brightblu.parc.model.AppEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure app-list state. Registers with a [LauncherSource], collapses callback bursts with a
 * trailing-edge debounce and republishes a label-sorted snapshot. Owns no Android types.
 * Thread-safe: apps and profiles are StateFlows; all mutation happens inside the supplied scope.
 *
 * The profile set is published before the app list on every reload, so an entry is never visible
 * with a profile marker from the previous scan. The set starts empty, which means "not read yet".
 */
internal class AppIndexCore(
    private val source: LauncherSource,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DefaultDebounceMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    initial: List<AppEntry> = emptyList(),
    initialProfiles: ProfileSet = ProfileSet(),
) {
    private val _apps = MutableStateFlow(initial)
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _profiles = MutableStateFlow(initialProfiles)
    val profiles: StateFlow<ProfileSet> = _profiles.asStateFlow()

    private val callback = LauncherCallback { scheduleReload() }
    private var initialJob: Job? = null
    private var reloadJob: Job? = null

    init {
        source.registerCallback(callback)
        initialJob = scope.launch { reload() }
    }

    /** Cancels this core's coroutines and stops listening for package events. */
    fun close() {
        initialJob?.cancel()
        reloadJob?.cancel()
        source.unregisterCallback(callback)
    }

    /** Re-reads apps and profiles now, without the debounce (for example on resume). */
    fun refresh() {
        initialJob?.cancel()
        reloadJob?.cancel()
        reloadJob = scope.launch { reload() }
    }

    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            delay(debounceMillis)
            reload()
        }
    }

    private suspend fun reload() {
        val snapshot = withContext(dispatcher) {
            SourceSnapshot(profiles = source.profiles(), activities = source.activities())
        }
        _profiles.value = snapshot.profiles.toProfileSet()
        _apps.value = snapshot.activities
            .map { it.toEntry() }
            .sortedWith(EntryOrder)
    }

    private data class SourceSnapshot(
        val profiles: SourceProfileSet,
        val activities: List<SourceActivity>,
    )

    internal companion object {
        const val DefaultDebounceMillis = 300L

        private val EntryOrder =
            compareBy(String.CASE_INSENSITIVE_ORDER) { entry: AppEntry -> entry.label }
                .thenBy { it.id.value }
    }
}

/** Maps a plain source snapshot to the frozen contract entry. */
internal fun SourceActivity.toEntry(): AppEntry = AppEntry(
    id = appIdOf(component, userSerial),
    label = label,
    packageName = packageName,
    isSystem = isSystem,
    installTime = installTime,
)
