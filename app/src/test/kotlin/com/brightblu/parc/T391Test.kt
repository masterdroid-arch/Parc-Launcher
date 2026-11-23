package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T391: panels that float over content are solid enough to read. */
class T391Test {
    private fun source(name: String): String {
        val dirs = listOf("src/main/kotlin/com/brightblu/parc", "app/src/main/kotlin/com/brightblu/parc")
        return dirs.map { File(it, name) }.first { it.exists() }.readText()
    }

    @Test fun floatingSurfaceIsAtLeastNinetyTwoPercentForEverySkinAlpha() {
        val base = Skin.Midnight
        for (alpha in listOf(0f, 0.3f, 0.5f, 0.92f, 1f)) {
            val skin = base.copy(shape = base.shape.copy(surfaceAlpha = alpha))
            val got = skin.floatingSurface().alpha
            assertTrue("alpha $alpha -> $got", got >= 0.92f)
            assertTrue(got >= minOf(1f, maxOf(alpha, Metrics.floatingPanelMinAlpha)) - 0.005f)
        }
    }

    @Test fun floatingSurfaceKeepsTheSurfaceColour() {
        val c = Skin.Midnight.floatingSurface()
        val s = androidx.compose.ui.graphics.Color(Skin.Midnight.colors.surface.toInt())
        assertEquals(s.red, c.red, 0.001f); assertEquals(s.green, c.green, 0.001f); assertEquals(s.blue, c.blue, 0.001f)
    }

    @Test fun noFloatingPanelDrawsTheRawSkinAlpha() {
        // The tips, menus, chips, snackbars and bars that float over Home all use the shared fill.
        for (file in listOf("FirstRunHint.kt", "LongPressMenu.kt", "NewAppPromptUi.kt", "EmptySlotSheet.kt", "FolderRename.kt", "GestureHints.kt")) {
            assertTrue("$file still draws the raw surfaceAlpha",
                !source(file).contains("copy(alpha = skin.shape.surfaceAlpha)"))
        }
        assertTrue(!source("LauncherHome.kt").contains("copy(alpha = skin.shape.surfaceAlpha)"))
    }
}
