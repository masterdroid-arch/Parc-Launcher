package com.brightblu.parc

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.withFolderHidden

/**
 * T218: which folder slots leave Home. A folder tagged `parc:hidden` always leaves with its apps. A
 * folder that has members also leaves while every member is hidden by permanent hide, Focus or
 * another hidden folder. An empty folder still draws as an empty folder.
 *
 * Lock is deliberately not a suppression source: a locked folder keeps its padlocked slot while its
 * apps are hidden, which is how it is unlocked again. A folder that is both locked and hidden still
 * leaves because the hide tag wins.
 *
 * The stored layout is only read. Nothing here moves a slot, a member or a pin.
 */
internal object FolderVisibility {
    fun hiddenFolderIds(layout: ParcLayout, apps: List<AppEntry>, visibility: Visibility): Set<FolderId> {
        val packages = apps.associate { it.id to it.packageName }
        val suppressed = visibility.permanentHidden + visibility.focusBlocked + visibility.hiddenFolderApps
        return layout.folders.asSequence()
            .filter { isHiddenFromHome(it, packages, suppressed) }
            .mapTo(linkedSetOf()) { it.id }
    }

    /** True when [folder] must not be drawn on Home under [suppressed] packages. */
    fun isHiddenFromHome(folder: Folder, packages: Map<AppId, String>, suppressed: Set<String>): Boolean {
        if (folder.hidden) return true
        if (folder.appIds.isEmpty()) return false
        return folder.appIds.all { id ->
            val packageName = packages[id] ?: id.value.substringBefore('/')
            packageName in suppressed
        }
    }
}

/**
 * Sets or clears a folder's `parc:hidden` tag. The frozen repository has no tag mutation, so the
 * whole document is validated through `importLayout`, exactly like the lock flag.
 */
internal suspend fun LayoutRepository.setFolderHiddenFlag(id: FolderId, hidden: Boolean) {
    val snapshot = layout.value
    val next = snapshot.withFolderHidden(id, hidden)
    if (next == snapshot) return
    runCatching { importLayout(LayoutJson.encode(next)) }
}
