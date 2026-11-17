package com.brightblu.parc

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T247: app icons and the long-press "Add to arc" actions in search. */
class T247Test {
    private val a = AppId("test/A@0")

    private fun source(path: String) = File(path).readText()

    private fun layout(
        geometry: RingLayout,
        occupied: Map<String, Map<Int, Slot>> = emptyMap(),
        folders: List<Folder> = emptyList(),
    ) = ParcLayout(
        arcs = geometry.arcs.map { ArcAssignment(it, occupied[it.id].orEmpty()) },
        folders = folders,
    )

    @Test fun theAppMenuOffersTheRowsInTheCardsOrder() {
        assertEquals(
            listOf("Add to arc", "Add to folder", "App info", "Set alias", "Uninstall", "Hide"),
            SearchAppMenu.actions(canUninstall = true).map { it.label },
        )
        // A preinstalled system app that cannot be uninstalled keeps the other rows.
        assertEquals(
            listOf("Add to arc", "Add to folder", "App info", "Set alias", "Hide"),
            SearchAppMenu.actions(canUninstall = false).map { it.label },
        )
    }

    @Test fun aHiddenFolderIsNotOfferedForAddToFolder() {
        val folders = listOf(Folder(FolderId("one"), "One"), Folder(FolderId("two"), "Two"))
        assertEquals(
            listOf("One"),
            SearchAppMenu.folders(folders, hiddenFolderIds = setOf(FolderId("two"))).map { it.name },
        )
    }

    @Test fun theArcPickerShowsEveryFreeSpotOnTheVisibleArcs() {
        val geometry = RingLayout(420f, 820f)
        val first = geometry.arcs.first()
        val value = layout(geometry, occupied = mapOf(first.id to mapOf(0 to Slot.App(a))))

        val spots = ArcPicker.spots(value, geometry.arcs)

        assertEquals(geometry.arcs.sumOf { it.slotCapacity } - 1, spots.size)
        assertTrue(spots.none { it.position == SlotPosition(first.id, 0) })
        assertTrue(spots.all { it.label.isNotBlank() })
        val spot = spots.first()
        assertEquals(spot.position, ArcPicker.hit(spots, spot.xDp, spot.yDp))
        assertNull(ArcPicker.hit(spots, -1_000f, -1_000f))
        assertNull(ArcPicker.hit(spots, Float.NaN, 0f))
    }

    @Test fun theArcPickerHonoursTheCapacityTheCurrentHubSizeShows() {
        val geometry = RingLayout(420f, 820f)
        val stored = geometry.arcs.first()
        val shown = stored.copy(slotCapacity = 2, slotAngles = listOf(180f, 270f))
        val value = ParcLayout(arcs = listOf(ArcAssignment(shown, mapOf(0 to Slot.App(a)))))

        assertEquals(
            listOf("${shown.id}/1"),
            ArcPicker.spots(value, listOf(shown)).map { "${it.position.arcId}/${it.position.index}" },
        )
    }

    @Test fun aFullArcSetHasNoPlusSpots() {
        val geometry = RingLayout(420f, 820f)
        val full = ParcLayout(arcs = geometry.arcs.map { arc ->
            ArcAssignment(arc, (0 until arc.slotCapacity).associateWith { Slot.App(AppId("test/$it@0")) })
        })
        assertTrue(ArcPicker.spots(full, geometry.arcs).isEmpty())
    }

    @Test fun theSearchSheetDrawsTheCachedArcIconAndHomeWiresTheActions() {
        val search = source("../feature/search/src/main/kotlin/com/brightblu/parc/feature/search/SearchSheet.kt")
        assertTrue(search.contains("combinedClickable"))
        assertTrue(search.contains("icon(row.appId)"))
        assertTrue(search.contains("iconRevision"))

        val home = source("src/main/kotlin/com/brightblu/parc/LauncherHome.kt")
        assertTrue(home.contains("icon = sceneIcon"))
        assertTrue(home.contains("iconRevision = revision"))
        assertTrue(home.contains("onAppLongPress ="))
        assertTrue(home.contains("SearchArcPicker("))
    }
}
