package com.brightblu.parc

import com.brightblu.parc.core.data.lock.LockedFolderText
import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import java.text.Normalizer
import java.util.Locale

/** Presentation projection only: persisted positions never change when the screen or index changes. */
internal object LauncherScenes {
    fun withUnread(scene: RingState, layout: ParcLayout, apps: List<AppEntry>, counts: Map<String, Int>,
        badgesEnabled: Boolean = true, badgesInAllApps: Boolean = false,
        /** T98: folders whose apps are hidden right now; their disc carries no count. */
        lockedFolders: Set<FolderId> = emptySet()): RingState {
        val visible = badgesEnabled && (scene.layer != Layer.AllApps || badgesInAllApps)
        val packages = apps.associate { it.id to it.packageName }
        fun count(ids: List<AppId>): Int = ids.mapNotNull { packages[it] }.distinct()
            .sumOf { (counts[it] ?: 0).coerceAtLeast(0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return scene.copy(slots = scene.slots.map { slot -> slot.copy(unreadCount = if (!visible) 0 else when (val item = slot.slot) {
            is Slot.App -> count(listOf(item.id))
            is Slot.Folder -> if (item.id in lockedFolders) 0 else count(layout.folders.firstOrNull { it.id == item.id }?.appIds.orEmpty()
                .filterNot { it in layout.hidden })
            else -> 0
        }) })
    }

    /**
     * T83: total scene builder. A failure anywhere in the projection is logged and recorded for
     * Parc Settings > Diagnostics, and the host receives a guaranteed A to Z All-apps list (or an
     * empty Home field) instead of the previous scene staying on screen while the shell says the
     * layer changed. A single broken icon never fails the whole scene.
     */
    fun build(geometry: RingLayout, layout: ParcLayout, apps: List<AppEntry>, shell: ShellState,
        clock: String, previews: Map<FolderId, android.graphics.Bitmap> = emptyMap(),
        rotations: Map<String, Float> = emptyMap(),
        rank: (FolderId) -> List<AppId> = { emptyList() },
        /** T75: profile membership decides the briefcase badge and the paused dim. */
        profiles: ProfileSet = ProfileSet(),
        /** T75 `Mark work apps`: off removes every work badge; the default keeps today's look. */
        markWorkApps: Boolean = true,
        /** T98: folders hidden right now show `Locked` instead of their name when [hideLockedName] is on. */
        lockedFolders: Set<FolderId> = emptySet(),
        hideLockedName: Boolean = false,
        shortcutVisuals: Map<Slot.Shortcut, PinnedShortcutVisual> = emptyMap(),
        shortcutHiddenPackages: Set<String> = emptySet(),
        icon: (AppId) -> android.graphics.Bitmap?): RingState = try {
        buildScene(geometry, layout, apps, shell, clock, previews, rotations, rank, profiles,
            markWorkApps, lockedFolders, hideLockedName, shortcutVisuals, shortcutHiddenPackages, icon)
    } catch (failure: Throwable) {
        val detail = "${shell.layer}/${sceneSlotCapacityHint(geometry)}: " +
            "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
        SceneDiagnostics.record(detail)
        logFailure("Scene build failed ($detail); using the safe fallback", failure)
        fallback(geometry, layout, apps, shell, clock, icon)
    }

    private fun buildScene(geometry: RingLayout, layout: ParcLayout, apps: List<AppEntry>, shell: ShellState,
        clock: String, previews: Map<FolderId, android.graphics.Bitmap>,
        rotations: Map<String, Float>, rank: (FolderId) -> List<AppId>,
        profiles: ProfileSet, markWorkApps: Boolean, lockedFolders: Set<FolderId>, hideLockedName: Boolean,
        shortcutVisuals: Map<Slot.Shortcut, PinnedShortcutVisual>, shortcutHiddenPackages: Set<String>,
        icon: (AppId) -> android.graphics.Bitmap?): RingState {
        fun folderLabel(folder: Folder): String =
            if (hideLockedName && folder.id in lockedFolders) LockedFolderText.HiddenName else folder.name
        val byId = apps.associateBy { it.id }
        val folders = layout.folders.associateBy { it.id }
        fun safeIcon(id: AppId): android.graphics.Bitmap? = try {
            icon(id)
        } catch (failure: Throwable) {
            SceneDiagnostics.record("Icon failed for ${id.value}: ${failure.javaClass.simpleName}")
            logFailure("Icon failed for ${id.value}", failure)
            null
        }
        fun appSlot(id: AppId): RingSlot? = byId[id]?.takeUnless { id in layout.hidden }?.let {
            RingSlot(SlotPosition("pending", 0), Slot.App(id), it.label, safeIcon(id),
                enabled = !it.suspended && !profiles.isPaused(id),
                isWork = workBadgeVisible(profiles.isWork(id), markWorkApps))
        }
        fun shortcutSlot(slot: Slot.Shortcut, position: SlotPosition): RingSlot? {
            if (slot.folderId in lockedFolders) return null
            // Keep an unavailable stub removable after its publisher is uninstalled. Policy-hidden
            // packages still leave the scene, including before the app index finishes refreshing.
            if (slot.packageName in shortcutHiddenPackages ||
                apps.any { it.packageName == slot.packageName && it.id in layout.hidden }) return null
            val visual = shortcutVisuals[slot]
            return RingSlot(position, slot, slot.label, visual?.icon, enabled = visual?.enabled == true)
        }
        if (shell.layer == Layer.Folder) {
            val folder = folders[shell.folderId]
            if (folder != null) {
                // T35: contents are alphabetical, from UsageRepository.rank (falling back to stored order).
                val ordered = (rank(folder.id).filter { it in folder.appIds } + folder.appIds).distinct()
                val shortcuts = layout.arcs.flatMap { it.slots.values }.filterIsInstance<Slot.Shortcut>()
                    .filter { it.folderId == folder.id }.mapNotNull { shortcutSlot(it, SlotPosition("pending", 0)) }
                return RingScenes.folder(geometry, folder, ordered.mapNotNull(::appSlot) + shortcuts, rotations)
            }
        }
        if (shell.layer == Layer.AllApps) {
            val ordered = allAppsOrder(apps, layout).map {
                RingSlot(SlotPosition("pending", 0), Slot.App(it.id), it.label, safeIcon(it.id),
                    enabled = !it.suspended && !profiles.isPaused(it.id),
                    isWork = workBadgeVisible(profiles.isWork(it.id), markWorkApps))
            }
            return RingScenes.allApps(geometry, ordered, rotations)
        }
        val slots = geometry.arcs.flatMap { arc ->
            layout.arcs.firstOrNull { it.arc.id == arc.id }?.slots.orEmpty().mapNotNull { (index, slot) ->
                // T36 lets a ring item sit past its arc's visible capacity; only a negative index is invalid.
                if (index < 0) return@mapNotNull null
                val position = SlotPosition(arc.id, index)
                when (slot) {
                    is Slot.App -> appSlot(slot.id)?.copy(position = position)
                    is Slot.Folder -> folders[slot.id]?.let { RingSlot(position, slot, folderLabel(it), previews[it.id]) }
                    is Slot.Action -> if (slot.kind == ActionKind.More) null else RingSlot(position, slot, when (slot.kind) {
                        ActionKind.Settings -> "Settings"
                        ActionKind.More -> "More"
                        ActionKind.AbcList -> "All apps"
                    })
                    is Slot.Link -> RingSlot(position, slot, slot.label)
                    is Slot.Shortcut -> if (slot.folderId == null || folders[slot.folderId] == null) shortcutSlot(slot, position) else null
                }
            }
        }.toMutableList()
        // The seeded inbox is unplaced. Use an empty outer position without moving a saved slot.
        val inbox = folders[FolderId("new")]
        if (inbox != null && inbox.appIds.isNotEmpty() && slots.none { it.slot == Slot.Folder(inbox.id) }) {
            val outer = geometry.arcs.last()
            val vacant = (0 until outer.slotCapacity).firstOrNull { i ->
                layout.arcs.firstOrNull { it.arc.id == outer.id }?.slots?.containsKey(i) != true
            }
            if (vacant != null) slots += RingSlot(SlotPosition(outer.id, vacant), Slot.Folder(inbox.id), folderLabel(inbox), previews[inbox.id])
        }
        return RingScenes.home(geometry, slots, clock, rotations)
    }

    /** The guaranteed scene: alphabetical apps for All-apps, an empty field otherwise. */
    private fun fallback(geometry: RingLayout, layout: ParcLayout, apps: List<AppEntry>, shell: ShellState,
        clock: String, icon: (AppId) -> android.graphics.Bitmap?): RingState = try {
        if (shell.layer == Layer.AllApps) {
            val ordered = apps.filterNot { it.id in layout.hidden }.distinctBy { it.id }
                .sortedWith(compareBy<AppEntry> { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.id.value })
                .map { entry ->
                    val bitmap = try { icon(entry.id) } catch (_: Throwable) { null }
                    RingSlot(SlotPosition("pending", 0), Slot.App(entry.id), entry.label, bitmap)
                }
            RingScenes.allApps(geometry, ordered)
        } else {
            val home = RingScenes.home(geometry, emptyList(), clock)
            // A failed folder build must not silently become Home while the shell says Folder.
            if (shell.layer == Layer.Home) home else home.copy(
                arcs = home.arcs.map { it.copy(layer = shell.layer, folderId = shell.folderId) },
                layer = shell.layer)
        }
    } catch (_: Throwable) {
        RingState(geometry.arcs.map { it.copy(layer = shell.layer) }, emptyList(), 0f, layer = shell.layer)
    }

    private fun sceneSlotCapacityHint(geometry: RingLayout): String =
        geometry.arcs.joinToString(",") { it.slotCapacity.toString() }

    /** Logcat in the app, a no-op where the Android framework is absent (JVM unit tests). */
    private fun logFailure(message: String, failure: Throwable) {
        ParcLog.e("Parc", message, failure)
    }
}

/**
 * All-apps ring order: alphabetical by label, case-insensitive (T35). Pinned-first, most-used and
 * recents ordering no longer applies. Hidden apps are excluded and never duplicated.
 */
internal fun allAppsOrder(apps: List<AppEntry>, layout: ParcLayout): List<AppEntry> =
    apps.filter { it.id !in layout.hidden }.distinctBy { it.id }
        .sortedWith(compareBy<AppEntry> { alphabeticalKey(it.label) }.thenBy { it.id.value })

internal fun alphabeticalKey(label: String): String =
    Normalizer.normalize(label, Normalizer.Form.NFD).filterNot {
        Character.getType(it) == Character.NON_SPACING_MARK.toInt()
    }.lowercase(Locale.ROOT)
