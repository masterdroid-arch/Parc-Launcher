package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T392: a page has the same margin on the left and the right (it was 20 dp left, 48 dp right). */
class T392Test {
    @Test fun leftAndRightMarginsAreEqualAtEveryCutout() {
        for (cutout in listOf(0f, 8f, 24f, 60f, -5f)) {
            val page = ParcSpacing.page(statusTopDp = 40f, navBottomDp = 24f, startInsetDp = cutout, endInsetDp = cutout)
            assertEquals(page.startDp, page.endDp, 0f)
        }
        assertEquals(Metrics.screenGutterDp, ParcSpacing.page(40f, 24f).endDp, 0f)
    }

    @Test fun contentFillsTheScreenWidthOnA384DpScreen() {
        val page = ParcSpacing.page(40f, 24f)
        val content = 384f - page.startDp - page.endDp
        assertEquals(344f, content, 0f)
    }

    @Test fun noSettingsScreenAddsItsOwnOneSidedPageMargin() {
        // The shared scaffold is the only place that sets the page's side margins.
        val blocks = File("../core/model/src/main/kotlin/com/brightblu/parc/model/ParcBlocks.kt").takeIf { it.exists() }
            ?: File("core/model/src/main/kotlin/com/brightblu/parc/model/ParcBlocks.kt")
        assertTrue(blocks.readText().contains(".padding(start = insets.startDp.dp, end = insets.endDp.dp)"))
        assertTrue(!ParcSpacingSource().contains("max(Metrics.keepOutRightDp"))
    }

    private fun ParcSpacingSource(): String =
        (File("../core/model/src/main/kotlin/com/brightblu/parc/model/ParcSpacing.kt").takeIf { it.exists() }
            ?: File("core/model/src/main/kotlin/com/brightblu/parc/model/ParcSpacing.kt")).readText()
}
