package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.locked

/**
 * One searchable app row. Immutable, non-null.
 *
 * [keywords] carries the folder names and tags the app belongs to (PROJECT.md §6.3 indexes folders
 * and tags too). The frozen [com.brightblu.parc.model.SearchHit] can only name an [AppId], so a
 * folder or tag hit surfaces its member apps rather than a folder row.
 */
data class SearchDoc(
    val appId: AppId,
    val label: String,
    val packageName: String,
    val keywords: List<String> = emptyList(),
)

/** Pure build of the search index from the installed apps and the current layout. */
object SearchIndexBuilder {

    /** Hidden apps (F-17) are not searchable and are not in the ABC list. */
    fun build(apps: List<AppEntry>, layout: ParcLayout): List<SearchDoc> {
        val keywords = keywordsByApp(layout)
        return apps
            .filterNot { it.id in layout.hidden }
            .map { entry ->
                SearchDoc(
                    appId = entry.id,
                    label = entry.label,
                    packageName = entry.packageName,
                    keywords = keywords[entry.id].orEmpty(),
                )
            }
    }

    private fun keywordsByApp(layout: ParcLayout): Map<AppId, List<String>> {
        val index = LinkedHashMap<AppId, MutableList<String>>()
        for (folder in layout.folders) {
            // T98: a locked folder's name and tags never become search keywords of its apps.
            if (folder.locked) continue
            val names = buildList {
                if (folder.name.isNotBlank()) add(folder.name)
                folder.tags.filterTo(this) { it.isNotBlank() }
            }
            if (names.isEmpty()) continue
            for (id in folder.appIds) index.getOrPut(id) { mutableListOf() }.addAll(names)
        }
        return index.mapValues { (_, values) -> values.distinct() }
    }
}
