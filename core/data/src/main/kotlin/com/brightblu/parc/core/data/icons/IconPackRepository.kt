package com.brightblu.parc.core.data.icons

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Icon pack discovery, selection and per-component icons.
 *
 * [packs] lists the installed packs; [selection] is the chosen package name or null for none.
 * [iconFor] returns the pack's bitmap for a flattened component at a pixel size, and falls back to
 * the app's own icon whenever the pack cannot answer. Bitmaps are cached under a byte budget, so a
 * pack change or re-read is the only way a drawable is rasterised twice.
 *
 * Selection lives in its own DataStore file ([StoreName]) with the default none. It is deliberately
 * not a `ParcPrefs` key: the launcher's general settings snapshot stays as it was, and the picker
 * (T71) can draw its own switch or list from [packs] and [selection].
 */
class IconPackRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val source: IconPackSource<Bitmap>,
    fallback: (component: String, sizePx: Int) -> Bitmap?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val logWarn: (String) -> Unit = {},
    maxBytes: Int = IconPackLoader.DefaultMaxBytes,
) {
    private val loader = IconPackLoader(
        source = source,
        sizeOf = { it.byteCount },
        fallback = fallback,
        logWarn = logWarn,
        maxBytes = maxBytes,
    )

    private val packsState = MutableStateFlow<List<IconPack>>(emptyList())
    val packs: StateFlow<List<IconPack>> = packsState.asStateFlow()

    private val selectionState = MutableStateFlow<String?>(null)
    val selection: StateFlow<String?> = selectionState.asStateFlow()

    private val jobs = ArrayList<Job>(2)

    init {
        jobs.add(
            scope.launch {
                store.data.collect { prefs ->
                    selectionState.value = IconPackRules.normalizeSelection(prefs[KeySelected])
                }
            },
        )
        // The list is only needed by the picker, so the read happens off the main thread and never
        // blocks the first frame.
        jobs.add(scope.launch(dispatcher) { loadPacks() })
    }

    /** Re-reads the installed pack list and drops parsed filters; safe to call from the picker. */
    suspend fun refresh() {
        withContext(dispatcher) { loadPacks() }
        loader.clear()
    }

    /** Selects a pack, or none when [packageName] is null or blank. Persisted immediately. */
    suspend fun select(packageName: String?) {
        val value = IconPackRules.normalizeSelection(packageName)
        store.edit { prefs ->
            if (value == null) prefs.remove(KeySelected) else prefs[KeySelected] = value
        }
        selectionState.value = value
    }

    /**
     * The icon for a flattened component, from the selected pack when it has one and from the app's
     * own icon otherwise. Never blocks the caller beyond a cache miss; returns null only when both
     * the pack and the fallback fail, which leaves the letter tile to the icon cache.
     */
    fun iconFor(component: String, sizePx: Int): Bitmap? =
        loader.iconFor(component, sizePx, selectionState.value)

    internal fun cachedBytes(): Int = loader.cachedBytes()

    /** Stops the selection and pack-list jobs. The repository lives for the process lifetime. */
    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun loadPacks() {
        val loaded = try {
            source.packs()
        } catch (e: Exception) {
            logWarn("icon pack list unreadable")
            emptyList()
        }
        packsState.value = loaded
    }

    companion object {
        const val StoreName = "parc_icon_packs"
        internal val KeySelected = stringPreferencesKey("selected_pack")

        /** Builds the process-wide repository for [context]; [fallback] supplies the app's own icon. */
        fun create(
            context: Context,
            scope: CoroutineScope,
            fallback: (component: String, sizePx: Int) -> Bitmap?,
        ): IconPackRepository {
            val app = context.applicationContext
            return IconPackRepository(
                store = app.parcIconPackStore,
                source = AndroidIconPackSource(app),
                fallback = fallback,
                scope = scope,
                dispatcher = Dispatchers.IO,
                logWarn = { Log.d(LauncherLogTag, it) },
            )
        }

        private const val LauncherLogTag = "Parc"
    }
}

internal val Context.parcIconPackStore by preferencesDataStore(name = IconPackRepository.StoreName,
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler(IconPackRepository.StoreName))
