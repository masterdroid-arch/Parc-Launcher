package com.brightblu.parc.core.data.media

/** What the transport is doing, reduced to the cases the Media page distinguishes. */
enum class MediaPlayState { Playing, Buffering, Paused, Stopped }

/**
 * A pure snapshot of one media session (T99). [positionMs] is the position reported at
 * [updatedElapsedMs] (SystemClock.elapsedRealtime base); [durationMs] <= 0 means unknown.
 * The cover is never part of this type: it lives in memory only and is held by the Android port.
 */
data class MediaSessionInfo(
    val id: String,
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val state: MediaPlayState,
    val positionMs: Long,
    val updatedElapsedMs: Long,
    val speed: Float,
    val durationMs: Long,
    val canPrevious: Boolean = true,
    val canNext: Boolean = true,
)

/** What a read of the active sessions produced. */
sealed interface MediaRead {
    /** Notification access is missing, so the platform refuses to list sessions. */
    data object NoAccess : MediaRead
    data class Sessions(val sessions: List<MediaSessionInfo>) : MediaRead
}

/** Source of sessions; the Android port and test fakes implement it. */
interface MediaSource {
    fun read(): MediaRead
}

/** Transport commands for a session by id. Each returns false when it could not be sent. */
interface MediaControls {
    fun playPause(id: String): Boolean
    fun previous(id: String): Boolean
    fun next(id: String): Boolean
    fun openApp(id: String): Boolean
}

/** Pure rules for ordering, progress and text. */
object MediaRules {
    /** Idle sessions with no title and nothing playing (some apps keep one open) are not worth a card. */
    fun visible(sessions: List<MediaSessionInfo>): List<MediaSessionInfo> =
        sessions.filterNot { it.state == MediaPlayState.Stopped && it.title.isNullOrBlank() }

    /** Playing (and buffering) first, then paused, then stopped; the system order is kept inside a group. */
    fun order(sessions: List<MediaSessionInfo>): List<MediaSessionInfo> =
        sessions.sortedBy { rank(it.state) }

    private fun rank(state: MediaPlayState): Int = when (state) {
        MediaPlayState.Playing -> 0
        MediaPlayState.Buffering -> 1
        MediaPlayState.Paused -> 2
        MediaPlayState.Stopped -> 3
    }

    /**
     * The session to show: the user's pick while it still exists, otherwise the first of the
     * ordered list. Returns null when there are no sessions.
     */
    fun selected(sessions: List<MediaSessionInfo>, pickedId: String?): MediaSessionInfo? {
        val ordered = order(sessions)
        return ordered.firstOrNull { it.id == pickedId } ?: ordered.firstOrNull()
    }

    /** Live position. Only a playing session advances; the result stays within a known duration. */
    fun positionMs(info: MediaSessionInfo, nowElapsedMs: Long): Long {
        var position = info.positionMs.coerceAtLeast(0L)
        if (info.state == MediaPlayState.Playing) {
            val elapsed = (nowElapsedMs - info.updatedElapsedMs).coerceAtLeast(0L)
            val speed = if (info.speed.isFinite()) info.speed.coerceIn(0f, 8f) else 1f
            position += (elapsed * speed).toLong()
        }
        return if (info.durationMs > 0) position.coerceAtMost(info.durationMs) else position
    }

    /** 0..1, or null when the duration is unknown (the bar is then not drawn). */
    fun progress(info: MediaSessionInfo, nowElapsedMs: Long): Float? {
        if (info.durationMs <= 0) return null
        return (positionMs(info, nowElapsedMs).toDouble() / info.durationMs).toFloat().coerceIn(0f, 1f)
    }

    /** `m:ss`, or `h:mm:ss` from one hour; negative is a dash pair. */
    fun time(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun elapsedText(info: MediaSessionInfo, nowElapsedMs: Long): String = time(positionMs(info, nowElapsedMs))
    fun durationText(info: MediaSessionInfo): String = if (info.durationMs > 0) time(info.durationMs) else "--:--"

    fun titleText(info: MediaSessionInfo): String = info.title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unknown title"

    /** Artist and album joined, or empty when neither is known. */
    fun subtitleText(info: MediaSessionInfo): String =
        listOfNotNull(info.artist?.trim()?.ifEmpty { null }, info.album?.trim()?.ifEmpty { null }).joinToString(" · ")

    fun stateText(state: MediaPlayState): String = when (state) {
        MediaPlayState.Playing -> "Playing"
        MediaPlayState.Buffering -> "Buffering"
        MediaPlayState.Paused -> "Paused"
        MediaPlayState.Stopped -> "Stopped"
    }

    /** True when the play/pause button should offer Pause. */
    fun showsPause(state: MediaPlayState): Boolean = state == MediaPlayState.Playing || state == MediaPlayState.Buffering

    /** The single line for an empty page, or null when something can be shown. */
    /**
     * OPUS6 (audit F2): sessions of apps hidden right now (a locked folder's apps, or apps the active
     * Focus mode blocks) are removed before anything is shown, picked, read for a cover or opened.
     */
    fun withoutHidden(read: MediaRead, hiddenPackages: Set<String>): MediaRead =
        if (read !is MediaRead.Sessions || hiddenPackages.isEmpty()) read
        else MediaRead.Sessions(read.sessions.filterNot { it.packageName in hiddenPackages })

    fun emptyLine(read: MediaRead?): String? = when (read) {
        null -> "Loading…"
        MediaRead.NoAccess -> "Needs notification access"
        is MediaRead.Sessions -> if (read.sessions.isEmpty()) "Nothing playing" else null
    }
}
