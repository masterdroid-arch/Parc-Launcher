package com.brightblu.parc

import com.brightblu.parc.core.data.lock.LockedApps
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingRotation
import com.brightblu.parc.ring.RingScenes
import org.junit.Assert.*
import org.junit.Test

/**
 * OPUS1: the All-apps view (hub tap, or the "All apps view" gesture) is one ring holding every app
 * that may be shown, A to Z, which turns to reach the rest. Built the way LauncherHome builds it.
 */
class AllAppsRingTest {
    private val geometry = RingLayout(384f, 853f)
    private fun app(pkg: String, label: String) = AppEntry(AppId("$pkg/Main@0"), label, pkg, false, 0)

    private fun scene(apps: List<AppEntry>, layout: ParcLayout): RingState {
        // Same projection as LauncherHome: apps of locked folders leave the list before the scene.
        val locked = LockedApps.lockedPackages(apps, layout.folders, unlocked = false)
        val visible = FocusFilter.visibleApps(apps, locked)
        return LauncherScenes.build(geometry, layout, visible, ShellState(layer = Layer.AllApps), "") { null }
    }

    @Test fun everyAppIsOnOneRingInAlphabeticalOrder() {
        val apps = listOf(app("p.zed", "Zed"), app("p.eclair", "Éclair"), app("p.alpha", "alpha"), app("p.mid", "Mid")) +
            List(30) { app("p.n$it", "Note %02d".format(it)) }
        val state = scene(apps, ParcLayout())
        assertEquals(1, state.arcs.size)
        val labels = state.slots.map { it.label }
        assertEquals(34, labels.size)
        assertEquals(listOf("alpha", "Éclair", "Mid"), labels.take(3))
        assertEquals("Zed", labels.last())
        assertTrue(state.slots.all { it.position.arcId == state.arcs.single().id })
        assertEquals((0 until 34).toList(), state.slots.map { it.position.index })
        assertTrue("34 apps overflow one arc, so it turns", RingRotation.rotates(34, state.arcs.single().slotCapacity))
    }

    @Test fun hiddenAppsAndLockedFolderAppsStayOut() {
        val bank = app("p.bank", "Bank")
        val diary = app("p.diary", "Diary")
        val secret = app("p.secret", "Secret")
        val mail = app("p.mail", "Mail")
        val vault = Folder(FolderId("vault"), "Vault", appIds = listOf(bank.id, diary.id)).withLocked(true)
        val layout = ParcLayout(hidden = setOf(secret.id), folders = listOf(vault))
        val state = scene(listOf(bank, diary, secret, mail), layout)
        assertEquals(listOf("Mail"), state.slots.map { it.label })
    }

    @Test fun anUnlockedFolderKeepsItsAppsOnTheRing() {
        val bank = app("p.bank", "Bank")
        val vault = Folder(FolderId("vault"), "Vault", appIds = listOf(bank.id))
        assertEquals(listOf("Bank"), scene(listOf(bank), ParcLayout(folders = listOf(vault))).slots.map { it.label })
    }

    @Test fun emptyListGivesAnEmptyRingWithAMessage() {
        val state = scene(emptyList(), ParcLayout())
        assertEquals(Layer.AllApps, state.layer)
        assertTrue(state.slots.isEmpty())
        assertEquals(1, state.arcs.size)
        assertEquals("No apps to show", state.emptyMessage)
    }

    @Test fun theRingRemembersItsTurnUnderAStableId() {
        val state = scene(List(20) { app("p.a$it", "A$it") }, ParcLayout())
        assertEquals(RingScenes.allAppsRingId(geometry.arcs.lastIndex), state.arcs.single().id)
    }
}
