package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RingSearchButtonPlacementTest {
    @Test fun buttonTouchTargetClearsEverySlotTargetOnEverySceneAndSize() {
        for ((width, height) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            val layout = RingLayout(width, height)
            val states = listOf(RingScenes.home(layout, emptyList()),
                RingScenes.folder(layout, Folder(FolderId("social"), "Social"), emptyList()),
                RingScenes.allApps(layout, emptyList()))
            for (state in states) {
                val button = RingSearchButtonPlacement(state.arcs)
                assertTrue(button.left >= 0f)
                assertTrue("bottom-left", button.centerY > height / 2f)
                assertTrue("inside the screen", button.centerY + button.radius <= height)
                val clearance = button.radius + Metrics.touchTargetDp / 2f
                for (arc in state.arcs) for (i in 0 until arc.slotCapacity) {
                    val angle = Math.toRadians(arc.slotAngle(i).toDouble())
                    val x = arc.originXDp + cos(angle).toFloat() * arc.radiusDp
                    val y = arc.originYDp + sin(angle).toFloat() * arc.radiusDp
                    val d = hypot(x - button.centerX, y - button.centerY)
                    assertTrue("$width/$height ${state.layer} ${arc.id}/$i d=$d", d >= clearance - 0.001f)
                }
            }
        }
    }

    @Test fun buttonIsHubSizedAndSitsInTheBottomLeftGutter() {
        assertEquals(44f, Metrics.searchButtonTouchDp, 0f)
        assertEquals(Metrics.hubRadiusDp, Metrics.searchButtonRadiusDp, 0f)
        assertEquals(Metrics.keepOutLeftDp, Metrics.searchButtonLeftDp, 0f)
        val button = RingSearchButtonPlacement(RingLayout(384f, 853f).arcs)
        assertEquals(Metrics.searchButtonLeftDp + Metrics.searchButtonRadiusDp, button.centerX, 0f)
    }
}
