package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.homeArcSpecs
import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class T105UsageTest {
    @Test fun lockedPackageCannotWinAnEmptySlotOrKeepALearnedSlotEvenAsAnotherActivity() {
        val hidden = AppId("private.pkg/Main@0"); val alias = AppId("private.pkg/Other@10"); val visible = AppId("public.pkg/Main@0")
        val arc = homeArcSpecs().first()
        val folder = Folder(FolderId("vault"),"Private",appIds = listOf(hidden)).withLocked(true)
        val empty = ParcLayout(arcs = listOf(ArcAssignment(arc, emptyMap())), folders = listOf(folder))
        val position = SlotPosition(arc.id,0)
        val scores = mapOf(hidden to 100.0, alias to 99.0, visible to 1.0)
        val installed = scores.keys
        assertEquals(visible, UsageLayerRanking.plan(listOf(position),empty,installed,scores).single().to)
        val occupied = empty.copy(arcs = listOf(ArcAssignment(arc,mapOf(0 to Slot.App(alias)))))
        assertEquals(visible, UsageLayerRanking.plan(listOf(position),occupied,installed,scores).single().to)
        val repaired = UsageLayerRanking.repairLockedSlots(occupied,listOf(position),installed,scores)
        assertEquals(Slot.App(visible), repaired.arcs.single().slots[0])
        val noCandidates = UsageLayerRanking.repairLockedSlots(occupied,listOf(position),setOf(hidden,alias),scores)
        assertTrue(noCandidates.arcs.single().slots.isEmpty())
    }
}
