package com.brightblu.parc.feature.settings.ui

import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.launch

/** Trailing-edge debounce, explicit flush on navigation, and bounded one-step undo. */
class EditorTiming(private val clock: () -> Long) {
    var generation: Long = 0L
        private set
    private var due: Long? = null
    private var restore: (() -> Unit)? = null
    private var expires = 0L
    fun changed() { generation++; due = clock() + Metrics.focusSaveDebounceMs }
    fun ready(): Boolean = due?.let { clock() >= it } == true
    fun saved(expectedGeneration: Long = generation) { if (expectedGeneration == generation) due = null }
    fun offerUndo(action: () -> Unit) { restore = action; expires = clock() + Metrics.focusUndoMs }
    fun undo(): Boolean {
        val action = restore; restore = null
        if (action == null || clock() >= expires) return false
        action(); return true
    }
}

/** Cancels only the wait, never an in-flight write. Closing flushes the last valid draft. */
class EditorWriter<T>(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
    private val write: suspend (T) -> Unit,
) {
    private var wait: kotlinx.coroutines.Job? = null
    private var pending: T? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()
    fun changed(value: T) {
        pending = value
        wait?.cancel()
        wait = scope.launchWait {
            kotlinx.coroutines.delay(Metrics.focusSaveDebounceMs)
            flush()
        }
    }
    fun discard() { wait?.cancel(); pending = null }
    fun flush() {
        val value = pending ?: return
        pending = null
        scope.launchWait {
            mutex.lock()
            try { write(value) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error) }
            finally { mutex.unlock() }
        }
    }
}
private fun kotlinx.coroutines.CoroutineScope.launchWait(block: suspend () -> Unit) =
    launch { block() }
