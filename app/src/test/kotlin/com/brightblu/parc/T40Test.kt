package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T40Test {
    @Test fun alphabeticalOrderIgnoresCaseAndDiacriticsAndIncludesFiledPinnedAppsOnce() {
        val labels = listOf("Zulu", "éclair", "BETA", "Álpha", "apple", "e\u0301cole", "hidden")
        val apps = labels.mapIndexed { i, label -> AppEntry(AppId("pkg/$i@0"), label, "pkg", false, 0) }
        val layout = ParcLayout(hidden = setOf(apps.last().id), pins = setOf(apps[0].id),
            folders = listOf(Folder(FolderId("filed"), "Filed", appIds = apps.map { it.id })))
        val ordered = allAppsOrder(apps + apps[0], layout)
        assertEquals(listOf("Álpha", "apple", "BETA", "éclair", "e\u0301cole", "Zulu"), ordered.map { it.label })
        val scene = LauncherScenes.build(RingLayout(384f, 853f), layout, apps,
            ShellState(layer = Layer.AllApps), "12:00") { null }
        assertEquals(ordered.map { Slot.App(it.id) }, scene.slots.map { it.slot })
        assertFalse(statsEligible(true, scene.layer, false))
    }

    @Test fun noticesExpireAndNewNoticesGetAFullLifetimeAndPauseClosesEditing() = runTest {
        val edit = RingEditSession(FakeLayoutRepository(), this)
        edit.enter()
        edit.notify("Moved")
        runCurrent()
        advanceTimeBy(Metrics.editNoticeMs - 1)
        assertEquals("Moved", edit.notice)
        edit.notify("Saved")
        runCurrent()
        advanceTimeBy(Metrics.editNoticeMs - 1)
        assertEquals("Saved", edit.notice)
        advanceTimeBy(1); runCurrent()
        assertEquals("", edit.notice)
        edit.notify("Saved")
        edit.onHostPaused()
        assertFalse(edit.enabled)
        assertEquals("", edit.notice)
        edit.enter(); edit.close() // Done / Back share this transition.
        assertFalse(edit.enabled)
    }
}
