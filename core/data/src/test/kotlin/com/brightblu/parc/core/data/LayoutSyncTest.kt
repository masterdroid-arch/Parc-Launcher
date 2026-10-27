package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSyncTest {

    private val reddit = appId("com.reddit.frontpage/launcher.default")
    private val chatter = appId("com.chatter/com.chatter.Main")
    private val newApp = appId("com.example.new/com.example.new.Main")
    private val otherNew = appId("com.example.other/com.example.other.Main")
    private val systemApp = appId("com.android.shell/com.android.shell.Main")
    private val social = folder("social", "Social", listOf(reddit))

    private fun pinnedLayout(): ParcLayout = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            when (spec.id) {
                "A1" -> ArcAssignment(spec, mapOf(0 to Slot.App(reddit), 3 to Slot.App(chatter)))
                "A2" -> ArcAssignment(spec, mapOf(4 to Slot.Action(com.brightblu.parc.model.ActionKind.Settings)))
                else -> ArcAssignment(spec, emptyMap())
            }
        },
        folders = listOf(social),
        pins = setOf(reddit, chatter),
    )

    private fun installed(ids: List<AppId>): List<AppEntry> = ids.map { appEntry(it) }

    @Test
    fun `an uninstalled app leaves its slot empty and moves no index`() {
        val before = pinnedLayout()

        val after = LayoutSync.reconcile(before, installed(listOf(reddit)), NewFolderId, NewFolderName)

        assertEquals(mapOf(0 to Slot.App(reddit)), after.arcs.first().slots)
        assertEquals(before.arcs.map { it.arc.id }, after.arcs.map { it.arc.id })
        assertEquals(setOf(reddit), after.pins)
    }

    @Test
    fun `every remaining slot keeps the exact index it had`() {
        val before = pinnedLayout()

        val after = LayoutSync.reconcile(before, installed(listOf(reddit, chatter)), NewFolderId, NewFolderName)

        assertEquals(before.arcs.map { it.slots.keys }, after.arcs.map { it.slots.keys })
    }

    @Test
    fun `folder lists lose apps that are gone and keep the stored order`() {
        val layout = pinnedLayout().let {
            it.copy(folders = listOf(folder("social", "Social", listOf(reddit, chatter))))
        }

        val after = LayoutSync.reconcile(layout, installed(listOf(chatter)), NewFolderId, NewFolderName)

        assertEquals(listOf(chatter), after.folders.single().appIds)
    }

    @Test
    fun `an unseen app is prepended to New, newest install first`() {
        val layout = pinnedLayout()
        val entries = listOf(
            appEntry(newApp, installTime = 100L),
            appEntry(otherNew, installTime = 300L),
        )

        val after = LayoutSync.reconcile(
            layout,
            installed(listOf(reddit, chatter)) + entries,
            NewFolderId,
            NewFolderName,
        )

        assertEquals(listOf(otherNew, newApp), after.folders.first { it.id == NewFolderId }.appIds)
    }

    @Test
    fun `New is created when the layout has none`() {
        val after = LayoutSync.reconcile(
            pinnedLayout(),
            installed(listOf(reddit, chatter, newApp)),
            NewFolderId,
            NewFolderName,
        )

        assertEquals("New", after.folders.last().name)
        assertEquals(listOf(newApp), after.folders.last().appIds)
    }

    @Test
    fun `an app that is already filed is not added to New`() {
        val after = LayoutSync.reconcile(
            pinnedLayout(),
            installed(listOf(reddit, chatter)),
            NewFolderId,
            NewFolderName,
        )

        assertTrue(after.folders.none { it.id == NewFolderId })
    }

    @Test
    fun `system apps stay out of New`() {
        val after = LayoutSync.reconcile(
            pinnedLayout(),
            installed(listOf(reddit, chatter)) + listOf(appEntry(systemApp, isSystem = true)),
            NewFolderId,
            NewFolderName,
        )

        assertTrue(after.folders.none { it.id == NewFolderId })
    }

    @Test
    fun `apps installed before the first seed stay unfiled`() {
        val baseline = appEntry(newApp, installTime = 999L)
        val after = appEntry(otherNew, installTime = 1_001L)

        val result = LayoutSync.reconcile(
            pinnedLayout(),
            installed(listOf(reddit, chatter)) + listOf(baseline, after),
            NewFolderId,
            NewFolderName,
            newSince = 1_000L,
        )

        val newFolder = result.folders.first { it.id == NewFolderId }
        assertEquals(listOf(otherNew), newFolder.appIds)
    }

    @Test
    fun `hidden apps stay out of New`() {        val layout = pinnedLayout().copy(hidden = setOf(newApp))

        val after = LayoutSync.reconcile(
            layout,
            installed(listOf(reddit, chatter, newApp)),
            NewFolderId,
            NewFolderName,
        )

        assertTrue(after.folders.none { it.id == NewFolderId })
        assertEquals(setOf(newApp), after.hidden)
    }

    @Test
    fun `an app at the top of New is not added twice`() {
        val layout = pinnedLayout()
        val entries = installed(listOf(reddit, chatter, newApp))

        val once = LayoutSync.reconcile(layout, entries, NewFolderId, NewFolderName)
        val twice = LayoutSync.reconcile(once, entries, NewFolderId, NewFolderName)

        assertEquals(once, twice)
    }

    @Test
    fun `reconciling a layout with nothing installed keeps the holes`() {
        val after = LayoutSync.reconcile(pinnedLayout(), emptyList(), NewFolderId, NewFolderName)

        assertEquals(homeArcSpecs().map { it.id }, after.arcs.map { it.arc.id })
        // The Settings action is not an app, so it stays where it is.
        assertEquals(listOf(emptySet<Int>(), setOf(4), emptySet<Int>(), emptySet<Int>()), after.arcs.map { it.slots.keys })
        assertTrue(after.pins.isEmpty())
        assertTrue(after.folders.single().appIds.isEmpty())
    }
}
