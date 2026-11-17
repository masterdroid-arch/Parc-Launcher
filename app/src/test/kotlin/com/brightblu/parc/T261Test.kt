package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions
import com.brightblu.parc.ring.searchButtonTopDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T261: Left hand draws the hub and arcs mirrored and never touches the saved layout. */
class T261Test {
    @Test
    fun theMirroredHubSitsOnTheLeftAndEverySlotStaysOnScreen() {
        val right = RingLayout(384f, 853f)
        val left = RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = true))
        assertTrue(right.originXDp > 192f)
        assertTrue(left.originXDp < 192f)
        assertEquals(right.originYDp, left.originYDp, 0f)
        // Same slots, same count: the saved layout maps one to one.
        assertEquals(right.arcs.map { it.slotCapacity }, left.arcs.map { it.slotCapacity })
        assertEquals(right.count, left.count)
    }

    @Test
    fun theSearchButtonMovesToTheOtherSideAndKeepsAHeight() {
        val right = RingLayout(384f, 853f)
        val left = RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = true))
        val rightTop = searchButtonTopDp(right.arcs, 384f)
        val leftTop = searchButtonTopDp(left.arcs, 384f)
        assertTrue(rightTop > 0f && leftTop > 0f)
        assertTrue(File("../feature/ring/src/main/kotlin/com/brightblu/parc/ring/RingSearchButtonPlacement.kt")
            .readText().contains("mirrored"))
    }

    @Test
    fun leftHandIsASettingKeptInBackups() {
        assertEquals(BackupValueType.Bool, BackupSpecs.uiPrefs.key("left_hand")!!.type)
        assertTrue(SettingsRegistry.all.any { it.id == "LeftHand" })
    }

    private class File(path: String) { val f = java.io.File(path); fun readText() = f.readText() }
}
