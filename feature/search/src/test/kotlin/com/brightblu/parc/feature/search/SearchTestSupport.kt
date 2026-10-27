package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deterministic app entry; id defaults to a stable flattened component + user serial. */
internal fun app(
    label: String,
    packageName: String = "pkg." + label.lowercase(),
    id: String = packageName + "/" + packageName + ".Main@0",
): AppEntry = AppEntry(AppId(id), label, packageName, isSystem = false, installTime = 0L)

/** Search document for the same identity rules as [app]. */
internal fun doc(
    label: String,
    packageName: String = "pkg." + label.lowercase(),
    id: String = packageName + "/" + packageName + ".Main@0",
    keywords: List<String> = emptyList(),
): SearchDoc = SearchDoc(AppId(id), label, packageName, keywords)

internal fun folder(id: String, name: String, tags: Set<String> = emptySet(), apps: List<AppEntry> = emptyList()) =
    Folder(FolderId(id), name, tags, apps.map { it.id })

internal fun layoutOf(vararg folders: Folder, hidden: Set<AppId> = emptySet(), pins: Set<AppId> = emptySet()) =
    ParcLayout(folders = folders.toList(), hidden = hidden, pins = pins)

/** Mutable test double; [FakeAppIndex] from the contract fixtures cannot change its list. */
internal class MutableAppIndex(entries: List<AppEntry> = emptyList()) : AppIndex {
    private val state = MutableStateFlow(entries.toList())
    override val apps: StateFlow<List<AppEntry>> = state.asStateFlow()
    val launches = mutableListOf<AppId>()

    fun set(entries: List<AppEntry>) {
        state.value = entries.toList()
    }

    override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
    override fun launch(id: AppId) {
        launches.add(id)
    }

    override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
}
