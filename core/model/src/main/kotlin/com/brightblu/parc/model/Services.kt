package com.brightblu.parc.model

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Shortcut metadata; immutable, non-null. id is scoped to its owning app. */
data class AppShortcut(val id: String, val label: String, val appId: AppId)

/**
 * Thread-safe app snapshot/cache. All values non-null except an absent cached icon.
 * icon is a non-blocking cache lookup; callers must not mutate/recycle returned bitmaps.
 * launch is called on main; shortcuts may be called off main (no permission yields an empty list).
 */
interface AppIndex {
    val apps: StateFlow<List<AppEntry>>
    fun icon(id: AppId, sizePx: Int): Bitmap?
    fun launch(id: AppId)
    fun shortcuts(id: AppId): List<AppShortcut>
}

/**
 * Thread-safe, atomic layout mutations; suspend work must not block main. All values non-null.
 * Missing IDs/invalid positions throw IllegalArgumentException. Import rejects unknown versions/keys.
 * Published collections are immutable snapshots. Export/import use LayoutJson's versioned schema.
 */
interface LayoutRepository {
    val layout: StateFlow<ParcLayout>
    suspend fun pin(id: AppId, position: SlotPosition)
    suspend fun unpin(id: AppId)
    suspend fun move(from: SlotPosition, to: SlotPosition)
    suspend fun createFolder(id: FolderId, name: String)
    suspend fun renameFolder(id: FolderId, name: String)
    suspend fun addToFolder(folderId: FolderId, id: AppId)
    suspend fun removeFromFolder(folderId: FolderId, id: AppId)
    suspend fun hide(id: AppId)
    suspend fun unhide(id: AppId)
    suspend fun exportLayout(): String
    suspend fun importLayout(source: String)
}

/** Thread-safe usage service. Non-null snapshots; rank already applies cold-start hysteresis. */
interface UsageRepository {
    fun recordLaunch(id: AppId)
    fun recents(limit: Int): Flow<List<AppId>>
    fun rank(folderId: FolderId): List<AppId>
}

/** Match offsets into the displayed label (UTF-16, start inclusive/end exclusive). Immutable, non-null. */
data class MatchRange(val start: Int, val endExclusive: Int)

/** Ranked match; higher score wins. Non-null; ranges must be an immutable snapshot. */
data class SearchHit(val appId: AppId, val score: Double, val matchRanges: List<MatchRange>)

/** Thread-safe in-memory search. Non-null, immutable results in descending score order. */
interface SearchEngine {
    fun query(text: String): List<SearchHit>
}

/** Thread-safe unread counts by package; non-null immutable snapshots, never notification content. */
interface NotificationState {
    val unread: StateFlow<Map<String, Int>>
}

/** Thread-safe active skin stream. Non-null validated, immutable snapshots. */
interface SkinProvider {
    val active: StateFlow<Skin>
}
