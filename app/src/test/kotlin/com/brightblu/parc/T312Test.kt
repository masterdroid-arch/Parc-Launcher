package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T312: per-arc "Maximum apps", the shared "Add to arc" chooser, and the backup round trip. */
class T312Test {
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")

    private fun arc(id: String, index: Int, capacity: Int = 4, layer: Layer = Layer.Home) =
        ArcSpec(id, index, 120f, 180f, 270f, capacity, layer)

    private fun layout(
        a1: Map<Int, Slot> = emptyMap(),
        a2: Map<Int, Slot> = emptyMap(),
        a3: Map<Int, Slot> = emptyMap(),
        a4: Map<Int, Slot> = emptyMap(),
        folders: List<Folder> = emptyList(),
        pins: Set<AppId> = emptySet(),
    ) = ParcLayout(
        arcs = listOf(
            ArcAssignment(arc("A1", 0), a1),
            ArcAssignment(arc("A2", 1), a2),
            ArcAssignment(arc("A3", 2), a3),
            ArcAssignment(arc("A4", 3), a4),
        ),
        folders = folders,
        pins = pins,
    )

    private fun arcsOf(vararg ids: String) = listOf("A1", "A2", "A3", "A4").filter { it in ids }
        .mapIndexed { i, id -> arc(id, i) }

    // ---- capacity and naming --------------------------------------------------------------

    @Test fun capacityIsAutoOrTheStoredNumber() {
        assertEquals(4, ArcChooser.capacity(4, ArcMaximums.Auto))
        assertEquals(0, ArcChooser.capacity(0, ArcMaximums.Auto))
        assertEquals(12, ArcChooser.capacity(4, 12))
        // A negative maximum is treated like Auto (the visible slots), never a lower bound.
        assertEquals(4, ArcChooser.capacity(4, -3))
        assertEquals(ArcMaximums.Max, ArcChooser.capacity(4, 100))
    }

    @Test fun arcNamesCountFromTheHubOutward() {
        assertEquals("Inner arc", ArcChooser.arcName(0, 4))
        assertEquals("Arc 2", ArcChooser.arcName(1, 4))
        assertEquals("Arc 3", ArcChooser.arcName(2, 4))
        assertEquals("Outer arc", ArcChooser.arcName(3, 4))
        // Three arcs: the third is the outermost.
        assertEquals("Inner arc", ArcChooser.arcName(0, 3))
        assertEquals("Arc 2", ArcChooser.arcName(1, 3))
        assertEquals("Outer arc", ArcChooser.arcName(2, 3))
    }

    // ---- codec and backup round trip -------------------------------------------------------

    @Test fun maximumsRoundTripThroughTheCodecAndAutoIsAbsent() {
        assertEquals(emptyMap<String, Int>(), ArcMaximums.decode(""))
        assertEquals(emptyMap<String, Int>(), ArcMaximums.decode("A1=0;A2=0"))
        assertEquals(mapOf("A2" to 12, "A4" to 8), ArcMaximums.decode("A4=8;A2=12;A1=0;A9=99"))
        assertEquals("A2=12;A4=8", ArcMaximums.encode(mapOf("A2" to 12, "A4" to 8, "A1" to 0)))
        // Values outside 1..24 are dropped on decode and never written on encode.
        assertEquals(mapOf("A2" to 12), ArcMaximums.decode("A2=12;A3=0;A4=25;A1=-1"))
    }

    @Test fun theSettingTravelsInTheBackupAndOldBackupsLoadAsAuto() = runTest {
        assertEquals(BackupValueType.Text, BackupSpecs.uiPrefs.key("arc_maximums")?.type)
        // An old backup without the key leaves the value absent, which is Auto.
        assertTrue(ArcMaximums.decode("").isEmpty())
        val source = FakeSharedPreferences()
        source.data["arc_maximums"] = "A2=12;A4=8"
        val read = SharedPreferencesStoreAccess(source).read(BackupSpecs.uiPrefs)
        assertEquals("A2=12;A4=8", read["arc_maximums"])
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, read)
        assertEquals("A2=12;A4=8", target.data["arc_maximums"])
    }

    // ---- occupancy --------------------------------------------------------------------------

    @Test fun theDisplayedNewFolderReservesTheOuterArcsFirstVacantVisibleSpot() {
        val inbox = Folder(FolderId(NewFolderKey), NewFolderName, appIds = listOf(a))
        val value = layout(a4 = mapOf(0 to Slot.App(b)), folders = listOf(inbox))
        assertEquals(SlotPosition("A4", 1), ArcChooser.newFolderSpot(value, "A4", newFolderHidden = false))
        // Hidden, empty or already placed New reserves nothing.
        assertNull(ArcChooser.newFolderSpot(value, "A4", newFolderHidden = true))
        assertNull(ArcChooser.newFolderSpot(value.copy(folders = listOf(inbox.copy(appIds = emptyList()))), "A4", false))
        val placed = layout(a4 = mapOf(0 to Slot.App(b), 1 to Slot.Folder(inbox.id)), folders = listOf(inbox))
        assertNull(ArcChooser.newFolderSpot(placed, "A4", false))
        // A full outer arc has no vacant visible spot.
        val fullOuter = (0 until 4).associateWith { Slot.App(AppId("test/o$it@0")) }
        assertNull(ArcChooser.newFolderSpot(layout(a4 = fullOuter, folders = listOf(inbox)), "A4", false))
    }

    @Test fun occupiedCountsSavedSlotsPlusTheDisplayedNewFolder() {
        val inbox = Folder(FolderId(NewFolderKey), NewFolderName, appIds = listOf(a))
        val value = layout(a4 = mapOf(0 to Slot.App(b)), folders = listOf(inbox))
        val spot = ArcChooser.newFolderSpot(value, "A4", false)!!
        assertEquals(setOf(0, 1), ArcChooser.occupiedIndices(value, "A4", spot))
        assertEquals(setOf(0), ArcChooser.occupiedIndices(value, "A4", null))
        // The New folder's spot is on the outer arc; it never touches another arc's occupancy.
        assertEquals(emptySet<Int>(), ArcChooser.occupiedIndices(value, "A1", spot))
    }

    // ---- first free spot --------------------------------------------------------------------

    @Test fun firstFreeSpotHonoursTheMaximumNotTheVisibleSlots() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)))
        val arcA1 = arc("A1", 0, capacity = 4)
        // Auto: the visible 4 slots; 0 is taken, so 1 is free.
        assertEquals(SlotPosition("A1", 1), ArcChooser.firstFreeSpot(value, arcA1, ArcMaximums.Auto, null))
        // Maximum 2: capacity 2, slot 0 taken, slot 1 free.
        assertEquals(SlotPosition("A1", 1), ArcChooser.firstFreeSpot(value, arcA1, 2, null))
        // Maximum 1 with 0 taken: full.
        assertNull(ArcChooser.firstFreeSpot(value, arcA1, 1, null))
    }

    @Test fun anArcCanOfferAnIndexBeyondItsVisibleSlotsWhenItsMaximumIsRaised() {
        val value = layout(a1 = (0 until 4).associateWith { Slot.App(AppId("test/f$it@0")) })
        val arcA1 = arc("A1", 0, capacity = 4)
        // Auto: all four visible slots taken -> full.
        assertNull(ArcChooser.firstFreeSpot(value, arcA1, ArcMaximums.Auto, null))
        // Maximum 8: the first free index beyond the visible window.
        assertEquals(SlotPosition("A1", 4), ArcChooser.firstFreeSpot(value, arcA1, 8, null))
    }

    @Test fun aLoweredMaximumKeepsItemsAndCountsAsFullOverByN() {
        val value = layout(a1 = (0 until 7).associateWith { Slot.App(AppId("test/x$it@0")) })
        val choices = ArcChooser.choices(value, arcsOf("A1"), mapOf("A1" to 4), null)
        assertEquals(1, choices.size)
        assertEquals(7, choices[0].occupied)
        assertEquals(4, choices[0].capacity)
        assertTrue(choices[0].full)
        assertEquals(3, choices[0].over)
        assertEquals("Inner arc \u00B7 Full", choices[0].label)
        // Nothing is ever deleted: the stored slots are untouched.
        assertEquals(7, value.arcs.first { it.arc.id == "A1" }.slots.size)
    }

    // ---- choices ----------------------------------------------------------------------------

    @Test fun choicesListEveryArcFromTheHubOutwardWithFreeCounts() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)), a2 = mapOf(0 to Slot.App(b), 1 to Slot.App(a)))
        val choices = ArcChooser.choices(value, arcsOf("A1", "A2", "A3", "A4"), emptyMap(), null)
        assertEquals(listOf("Inner arc", "Arc 2", "Arc 3", "Outer arc"), choices.map { it.name })
        assertEquals("Inner arc \u00B7 1 of 4", choices[0].label)
        assertEquals("Arc 2 \u00B7 2 of 4", choices[1].label)
        assertEquals("Arc 3 \u00B7 0 of 4", choices[2].label)
        assertEquals("Outer arc \u00B7 0 of 4", choices[3].label)
    }

    @Test fun threeArcsMakeThreeRowsAndTheThirdIsOuter() {
        val value = layout()
        val choices = ArcChooser.choices(value, arcsOf("A1", "A2", "A3"), emptyMap(), null)
        assertEquals(listOf("Inner arc", "Arc 2", "Outer arc"), choices.map { it.name })
    }

    @Test fun aMirroredLayoutKeepsTheHubOutwardOrder() {
        // Left hand only mirrors the drawing; the chooser still lists inner to outer.
        val value = layout()
        val mirrored = listOf(
            arc("A1", 0, 4, Layer.Home),
            arc("A2", 1, 4, Layer.Home),
            arc("A3", 2, 4, Layer.Home),
            arc("A4", 3, 4, Layer.Home),
        )
        assertEquals(listOf("Inner arc", "Arc 2", "Arc 3", "Outer arc"),
            ArcChooser.choices(value, mirrored, emptyMap(), null).map { it.name })
    }

    @Test fun everyArcFullIsReported() {
        val value = layout(
            a1 = (0 until 4).associateWith { Slot.App(AppId("test/w$it@0")) },
            a2 = (0 until 4).associateWith { Slot.App(AppId("test/x$it@0")) },
            a3 = (0 until 4).associateWith { Slot.App(AppId("test/y$it@0")) },
            a4 = (0 until 4).associateWith { Slot.App(AppId("test/z$it@0")) },
        )
        val choices = ArcChooser.choices(value, arcsOf("A1", "A2", "A3", "A4"), emptyMap(), null)
        assertTrue(choices.all { it.full })
    }

    @Test fun theLastFreeSpotWithNewShownIsReserved() {
        val inbox = Folder(FolderId(NewFolderKey), NewFolderName, appIds = listOf(a))
        // A4 holds apps at 0, 1 and 2; New borrows 3, so no free visible spot remains.
        val value = layout(a4 = mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(a)),
            folders = listOf(inbox))
        val spot = ArcChooser.newFolderSpot(value, "A4", false)!!
        val arcA4 = arc("A4", 3, capacity = 4)
        assertNull(ArcChooser.firstFreeSpot(value, arcA4, ArcMaximums.Auto, spot))
        // Without the New reservation the same slot would look free.
        assertEquals(SlotPosition("A4", 3), ArcChooser.firstFreeSpot(value, arcA4, ArcMaximums.Auto, null))
    }

    // ---- placement --------------------------------------------------------------------------

    @Test fun placementAddsANewAppToTheFirstFreeSpotAndPinsIt() {
        val value = layout(a1 = mapOf(0 to Slot.App(b)))
        val placed = ArcChooser.placement(value, a, "A1", ArcMaximums.Auto, null)!!
        assertEquals(Slot.App(a), placed.layout.arcs.first { it.arc.id == "A1" }.slots[1])
        assertTrue(a in placed.layout.pins)
        assertEquals(1, placed.index)
    }

    @Test fun placementMovesAnAppAlreadyOnAnotherArcNeverDuplicatingIt() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)))
        val placed = ArcChooser.placement(value, a, "A2", ArcMaximums.Auto, null)!!
        assertFalse(placed.layout.arcs.first { it.arc.id == "A1" }.slots.containsValue(Slot.App(a)))
        assertEquals(Slot.App(a), placed.layout.arcs.first { it.arc.id == "A2" }.slots[0])
    }

    @Test fun placementKeepsAnAppOnItsCurrentArc() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)))
        val placed = ArcChooser.placement(value, a, "A1", ArcMaximums.Auto, null)!!
        assertEquals(value, placed.layout)
        assertEquals(0, placed.index)
    }

    @Test fun aFullArcRefusesAndAStaleFullArcRefusesAtSaveTime() {
        val full = layout(a1 = (0 until 4).associateWith { Slot.App(AppId("test/f$it@0")) })
        assertNull(ArcChooser.placement(full, a, "A1", ArcMaximums.Auto, null))
        // A layout that filled while the chooser was open still refuses the write.
        assertNull(ArcChooser.placement(full, a, "A1", ArcMaximums.Auto, null))
    }

    // ---- reveal -----------------------------------------------------------------------------

    @Test fun revealOffsetOnlyAppliesToARotatingArc() {
        val occupied = listOf(0, 1, 2, 3, 4)
        // 5 items in 4 visible slots: the arc rotates, and item 4 comes to the front (shortest turn).
        assertEquals(-1f, ArcChooser.revealOffset(4, occupied, 4, 0f))
        // 4 items in 4 visible slots: no rotation, no reveal.
        assertNull(ArcChooser.revealOffset(3, listOf(0, 1, 2, 3), 4, 0f))
    }

    @Test fun messagesNameTheArcAndOfferTheSettingsShortcut() {
        assertEquals("Inner arc is full. Choose another arc or raise its maximum in Settings.",
            ArcChooser.fullMessage("Inner arc"))
        assertEquals("All arcs are full. Remove an app or raise a maximum in Settings.", ArcChooser.AllFullMessage)
        assertEquals("Added to Inner arc", ArcChooser.addedMessage("Inner arc"))
    }
}
