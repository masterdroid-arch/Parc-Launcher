package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.ParcLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Connects the pure folder editor to the layout (T11).
 *
 * Threading: [dispatch] and [state] are main-confined, the same way a Compose screen uses them; the
 * write itself is suspended off the caller's frame through [scope]. Writes are serialized by a mutex,
 * so two quick events cannot interleave their repository calls (a reorder is several calls).
 *
 * The layout stays the source of truth: while no write is in flight, a new layout snapshot rebuilds
 * the document (which is what the repository holds after a successful write). An edit the frozen
 * contract cannot store is reverted and [FolderEditorState.notice] explains why.
 */
class FolderEditorController(
    private val writer: FolderEditWriter,
    val apps: StateFlow<List<AppEntry>>,
    private val layout: StateFlow<ParcLayout>,
    arcCapacities: List<Int>,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(
        FolderEditorState(arcCapacities, FolderDocument.from(layout.value)),
    )
    val state: StateFlow<FolderEditorState> = stateFlow.asStateFlow()

    private val writes = Mutex()
    private var writesInFlight = 0

    init {
        scope.launch {
            layout.collect { snapshot ->
                if (writesInFlight != 0) return@collect
                val document = FolderDocument.from(snapshot)
                if (stateFlow.value.document != document) {
                    stateFlow.value = stateFlow.value.copy(document = document)
                }
            }
        }
    }

    /** Reduces [event] and persists any document change. Safe to call from the UI thread. */
    fun dispatch(event: FolderEditorEvent) {
        val before = stateFlow.value
        val after = FolderEditorMachine.reduce(before, event)
        if (after == before) return
        stateFlow.value = after
        if (after.document == before.document) return

        writesInFlight++
        scope.launch {
            val result = writes.withLock { writer.apply(before.document, after.document) }
            writesInFlight--
            if (result is FolderEditResult.Unsupported) {
                stateFlow.value = stateFlow.value.copy(
                    document = FolderDocument.from(layout.value),
                    notice = result.reason,
                )
            }
        }
    }
}
