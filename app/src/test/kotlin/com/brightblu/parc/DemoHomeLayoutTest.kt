package com.brightblu.parc

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoHomeLayoutTest {

    private fun arcs(): List<ArcSpec> = listOf(4, 5, 7, 9).mapIndexed { index, capacity ->
        ArcSpec(
            id = "A${index + 1}",
            index = index,
            radiusDp = 0f,
            startAngle = 0f,
            endAngle = 0f,
            slotCapacity = capacity,
            layer = Layer.Home,
        )
    }

    @Test
    fun demoOffNeverReturnsTheSampleOrItsApps() {
        assertNull(DemoHomeLayout.layoutIfDemo(false, arcs()))
        assertTrue(DemoHomeLayout.appsIfDemo(false).isEmpty())

        val real = RecordingLayoutStore()
        assertSame(real, DemoHomeLayout.select(false, arcs()) { real })
    }

    @Test
    fun demoOnNeverReadsOrWritesTheRealLayoutStore() {
        val real = RecordingLayoutStore()
        var realFactoryCalls = 0
        val store = DemoHomeLayout.select(true, arcs()) {
            realFactoryCalls++
            real
        }

        assertEquals(0, realFactoryCalls)
        assertEquals(0, real.touched)
        assertNotNull(DemoHomeLayout.layoutIfDemo(true, arcs()))
        assertEquals(DemoHomeLayout.sample(arcs()), store.layout.value)

        runBlocking {
            store.load(emptyList())
            store.syncInstalled(emptyList())
            store.updateLayout { it.copy(hidden = setOf(AppId("demo/notes"))) }
            store.pin(AppId("demo/notes"), SlotPosition("A1", 0))
            store.unpin(AppId("demo/notes"))
            store.move(SlotPosition("A1", 0), SlotPosition("A1", 1))
            store.createFolder(FolderId("extra"), "Extra")
            store.renameFolder(FolderId("extra"), "Extras")
            store.addToFolder(FolderId("social"), AppId("demo/notes"))
            store.removeFromFolder(FolderId("social"), AppId("demo/notes"))
            store.hide(AppId("demo/maps"))
            store.unhide(AppId("demo/maps"))
            val exported = store.exportLayout()
            store.importLayout(exported)
        }

        assertEquals(0, realFactoryCalls)
        assertEquals(0, real.touched)
    }

    @Test
    fun sampleHasTwelvePinnedAppsAndFourNamedFolders() {
        val layout = DemoHomeLayout.sample(arcs())
        val slots = layout.arcs.flatMap { it.slots.values }

        assertEquals(12, layout.pins.size)
        assertEquals(12, slots.filterIsInstance<Slot.App>().size)
        assertEquals(4, slots.filterIsInstance<Slot.Folder>().size)
        assertEquals(listOf("Social", "Tools", "Media", "Games"), layout.folders.map { it.name })
        assertTrue(layout.folders.all { it.appIds.size in 3..4 })

        val appIds = DemoHomeLayout.appsIfDemo(true).map { it.id }.toSet()
        assertEquals(12, slots.filterIsInstance<Slot.App>().map { it.id }.toSet().size)
        assertTrue(slots.filterIsInstance<Slot.App>().all { it.id in appIds })
        assertTrue(layout.folders.flatMap { it.appIds }.all { it in appIds })
    }

    @Test
    fun sampleAppsAreDistinctGenericPlaceholders() {
        val apps = DemoHomeLayout.appsIfDemo(true)

        assertEquals(apps.size, apps.map { it.id }.toSet().size)
        assertEquals(apps.size, apps.map { it.label }.toSet().size)
        assertTrue(apps.all { it.label.isNotBlank() })
        assertTrue(apps.all { it.packageName.startsWith("parc.demo.") })
        assertTrue(("Contacts" in apps.map { it.label }).not())
    }

    @Test
    fun everySampleAppHasADemoIconAndRealModeDoesNot() {
        val apps = DemoHomeLayout.appsIfDemo(true)
        assertTrue(apps.all { com.brightblu.parc.core.data.demo.DemoIconCatalog.specFor(it.id) != null })
        assertTrue(apps.all { com.brightblu.parc.core.data.demo.DemoIcons.shouldDraw(true, it.id) })
        assertTrue(apps.none { com.brightblu.parc.core.data.demo.DemoIcons.shouldDraw(false, it.id) })
        assertEquals(null, DemoHomeLayout.labelFor(AppId("com.android.chrome")))
        assertEquals("Notes", DemoHomeLayout.labelFor(AppId("demo/notes")))
    }

    private class RecordingLayoutStore : LayoutStore {
        override val loaded: Boolean get() = true
        var touched = 0
            private set

        private val state = MutableStateFlow(ParcLayout())
        override val layout: StateFlow<ParcLayout> = state.asStateFlow()

        override suspend fun load(installed: List<AppEntry>) { touched++ }
        override suspend fun syncInstalled(installed: List<AppEntry>) { touched++ }
        override suspend fun updateLayout(change: (ParcLayout) -> ParcLayout) { touched++ }
        override suspend fun pin(id: AppId, position: SlotPosition) { touched++ }
        override suspend fun unpin(id: AppId) { touched++ }
        override suspend fun move(from: SlotPosition, to: SlotPosition) { touched++ }
        override suspend fun createFolder(id: FolderId, name: String) { touched++ }
        override suspend fun renameFolder(id: FolderId, name: String) { touched++ }
        override suspend fun addToFolder(folderId: FolderId, id: AppId) { touched++ }
        override suspend fun removeFromFolder(folderId: FolderId, id: AppId) { touched++ }
        override suspend fun hide(id: AppId) { touched++ }
        override suspend fun unhide(id: AppId) { touched++ }
        override suspend fun exportLayout(): String { touched++; return "" }
        override suspend fun importLayout(source: String) { touched++ }
    }
}
