package com.brightblu.parc.reader

import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderTtsRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** T257: what the reader shows while the system voice reads. All offsets are chapter text offsets. */
internal enum class ReaderTtsPhase { Idle, Preparing, Playing, Paused, Finished, Unavailable }

internal data class ReaderTtsState(
    val bookId: String = "",
    val phase: ReaderTtsPhase = ReaderTtsPhase.Idle,
    val chapter: Int = 0,
    val chunkStart: Int = 0,
    val chunkEnd: Int = 0,
    val start: Int = 0,
    val end: Int = 0,
    val speed: Float = ReaderTtsRules.DefaultSpeed,
    val stopAtChapterEnd: Boolean = false,
    val message: String = "",
) {
    val active: Boolean get() = phase != ReaderTtsPhase.Idle && phase != ReaderTtsPhase.Finished

    fun sameBook(id: String): Boolean = bookId == id && phase != ReaderTtsPhase.Idle
}

/**
 * One reader at a time, so the service publishes its state through this small process-wide holder.
 * The reader activity observes it for the play sheet and the follow-along highlight; the service
 * owns the actual playback. No text is stored here beyond the loaded book in [ReaderTtsSession].
 */
internal object ReaderTtsStateHolder {
    private val current = MutableStateFlow(ReaderTtsState())
    val state: StateFlow<ReaderTtsState> = current.asStateFlow()

    fun set(value: ReaderTtsState) { current.value = value }

    fun update(change: (ReaderTtsState) -> ReaderTtsState) { current.value = change(current.value) }
}

/** The loaded book handed to the foreground service in the same process, never through a Binder. */
internal data class ReaderTtsSession(
    val book: Book,
    val doc: ReaderBookDoc,
    val unadded: Boolean,
    val openHash: String?,
)

internal object ReaderTtsContent {
    @Volatile var session: ReaderTtsSession? = null

    fun put(value: ReaderTtsSession) { session = value }
    fun clear() { session = null }
}
