package com.brightblu.parc

import androidx.compose.runtime.staticCompositionLocalOf
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.hidden

/**
 * T217: one visibility projection for every surface that names apps. A package in [hidden] leaves
 * the arcs, folders, search, All apps, Messages, Media, the long-press menus and pinned shortcuts.
 * [messageBlocked] is the Messages rule: permanent hide, Lock and a hidden folder cannot be
 * overridden; a Focus exception only lifts the Focus block; the explicit `hiddenComms` list always
 * hides.
 *
 * Immutable and pure. The stored layout, the Focus mode and the unlock window are read, never
 * rewritten: hiding something here never moves a slot, a pin or a folder member.
 */
internal data class Visibility(
    val permanentHidden: Set<String> = emptySet(),
    val focusBlocked: Set<String> = emptySet(),
    val lockedNow: Set<String> = emptySet(),
    val hiddenFolderApps: Set<String> = emptySet(),
    val messageBlocked: Set<String> = emptySet(),
) {
    /** Every package hidden on every surface, in the card's precedence order. */
    val hidden: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        permanentHidden + focusBlocked + lockedNow + hiddenFolderApps
    }

    fun isHidden(packageName: String): Boolean = packageName in hidden

    companion object {
        /** Reserved [com.brightblu.parc.model.Folder.tags] entry, like `parc:locked` (T218 adds the UI). */
        const val HiddenFolderTag = com.brightblu.parc.model.HiddenFolderTag

        /** The one computation used by the launcher and by tests. */
        fun of(apps: List<AppEntry>, layout: ParcLayout, mode: FocusMode?,
            lockedNow: Set<String>): Visibility {
            val permanent = permanentHiddenPackages(apps, layout)
            val folderHidden = hiddenFolderPackages(apps, layout)
            val focus = focusBlockedPackages(mode, apps, layout)
            val alwaysShow = mode?.alwaysShowMessagePackages.orEmpty()
            val hiddenComms = mode?.hiddenComms.orEmpty()
            val messageBlocked = permanent + lockedNow + folderHidden +
                (focus - alwaysShow) + hiddenComms
            return Visibility(
                permanentHidden = permanent,
                focusBlocked = focus,
                lockedNow = lockedNow,
                hiddenFolderApps = folderHidden,
                messageBlocked = messageBlocked,
            )
        }

        /** Packages for the apps whose ids are in `layout.hidden`. */
        fun permanentHiddenPackages(apps: List<AppEntry>, layout: ParcLayout): Set<String> {
            if (layout.hidden.isEmpty()) return emptySet()
            val byId = apps.associate { it.id to it.packageName }
            return layout.hidden.mapTo(LinkedHashSet()) { byId[it] ?: it.value.substringBefore('/') }
        }

        /** Packages of apps inside a folder tagged [HiddenFolderTag]. */
        fun hiddenFolderPackages(apps: List<AppEntry>, layout: ParcLayout): Set<String> {
            if (layout.folders.none { it.hidden }) return emptySet()
            val byId = apps.associate { it.id to it.packageName }
            return layout.folders.asSequence()
                .filter { it.hidden }
                .flatMap { it.appIds.asSequence() }
                .mapTo(LinkedHashSet()) { byId[it] ?: it.value.substringBefore('/') }
        }
    }
}

/** The one visibility value the launcher provides to Messages, Media and every app picker. */
internal val LocalVisibility = staticCompositionLocalOf { Visibility() }
