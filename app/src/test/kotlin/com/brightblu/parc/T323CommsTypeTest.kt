package com.brightblu.parc

import androidx.compose.ui.text.font.FontWeight
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcFonts
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson
import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.model.compositeOver
import com.brightblu.parc.model.contrastRatio
import com.brightblu.parc.model.readableAccent
import com.brightblu.parc.model.textFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T323: Messages asks for the six approved roles and Figtree, with no size, weight or family of its own. */
class T323CommsTypeTest {

    private val root = sequenceOf(File("."), File(".."))
        .first { File(it, "app/src/main/kotlin/com/brightblu/parc/CommsPage.kt").isFile }

    private fun source(relative: String) = File(root, relative).readText()

    private fun skinDirectory(): File =
        sequenceOf(File(root, "../core/data/src/main/assets/skins"), File(root, "core/data/src/main/assets/skins"))
            .first { it.isDirectory }

    private fun shippedSkins(): List<Skin> = skinDirectory().listFiles { file -> file.extension == "json" }!!
        .sortedBy { it.name }
        .map { SkinJson.decode(it.readText()) }

    @Test fun eachRoleIsTheSkinTokenInScaleOrder() {
        val typography = Skin.Midnight.typography.copy(
            clock = TypeToken(47f, 200, -0.4f),
            title = TypeToken(31f, 300, 0f),
            countDot = TypeToken(33f, 600, 0f),
            section = TypeToken(19f, 500, 0.1f),
            body = TypeToken(16f, 400, 0f),
            caption = TypeToken(12f, 500, 0.3f),
        )
        val skin = Skin.Midnight.copy(typography = typography)
        assertEquals(
            listOf(
                TypeToken(47f, 200, -0.4f),
                TypeToken(31f, 300, 0f),
                TypeToken(33f, 600, 0f),
                TypeToken(19f, 500, 0.1f),
                TypeToken(16f, 400, 0f),
                TypeToken(12f, 500, 0.3f),
            ),
            CommsType.all(skin),
        )
    }

    @Test fun theDefaultSkinUsesTheApprovedScale() {
        val skin = Skin.Midnight
        assertEquals(TypeToken(44f, 200, -0.5f), CommsType.clock(skin))
        assertEquals(TypeToken(28f, 300, 0f), CommsType.title(skin))
        assertEquals(TypeToken(28f, 600, 0f), CommsType.stat(skin))
        assertEquals(TypeToken(17f, 500, 0f), CommsType.section(skin))
        assertEquals(TypeToken(15f, 400, 0f), CommsType.body(skin))
        assertEquals(TypeToken(11f, 500, 0.3f), CommsType.caption(skin))
    }

    @Test fun aStyleCarriesTheRoleSizeWeightTrackingAndFigtree() {
        val typography = Skin.Midnight.typography.copy(section = TypeToken(19f, 500, 0.2f))
        val skin = Skin.Midnight.copy(typography = typography)
        val style = commsStyle(skin, CommsType.section(skin), skin.colors.accent)
        assertEquals(19f, style.fontSize.value, 0f)
        assertEquals(FontWeight(500), style.fontWeight)
        assertEquals(0.2f, style.letterSpacing.value, 0f)
        assertEquals(ParcFonts.Figtree, style.fontFamily)
        assertEquals(skin.textFamily, style.fontFamily)
    }

    @Test fun everyMessageLineNamesARoleAndNothingSetsItsOwnType() {
        val page = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")
        assertFalse("no raw size", page.contains("fontSize ="))
        assertFalse("no raw sp unit", Regex("""\.sp\b""").containsMatchIn(page))
        assertFalse("no raw weight", page.contains("FontWeight("))
        assertFalse("dialogs use the same roles", page.contains("settingsText("))
        assertFalse("no family picked here", page.contains("FontFamily"))
        // The four roles Messages needs are all present; clock and stat are numerals this page has none of.
        assertTrue(page.contains("CommsType.title("))
        assertTrue(page.contains("CommsType.section("))
        assertTrue(page.contains("CommsType.body("))
        assertTrue(page.contains("CommsType.caption("))
    }

    @Test fun tabsChipsAndLongPressTargetsReachTheMinimumTouchSize() {
        assertTrue("the 48 dp minimum is at least 48 dp", Metrics.minTouchDp >= 48f)
        assertTrue("actions already use the larger target", Metrics.commsActionDp >= Metrics.minTouchDp)
        val page = source("app/src/main/kotlin/com/brightblu/parc/CommsPage.kt")
        val tab = page.substringAfter("private fun CommsTextTab")
            .substringBefore("private fun CommsCardHeader")
        assertTrue(tab.contains("heightIn(min = Metrics.minTouchDp.dp)"))
        assertTrue(tab.contains("widthIn(min = Metrics.minTouchDp.dp)"))
        val chips = page.substringAfter("private fun ReplyChips")
            .substringBefore("private fun CommsMessageMenuDialog")
        assertTrue(chips.contains("heightIn(min = Metrics.minTouchDp.dp)"))
        assertTrue(chips.contains("widthIn(min = Metrics.minTouchDp.dp"))
        val header = page.substringAfter("private fun CommsCardHeader")
        assertTrue(header.contains("heightIn(min = Metrics.minTouchDp.dp)"))
    }

    @Test fun cardSecondaryTextAndActionTextStayReadableOnEveryShippedSkin() {
        val skins = shippedSkins()
        assertEquals(9, skins.size)
        for (skin in skins) {
            val card = compositeOver(skin.colors.surface, skin.colors.bg)
            assertTrue("${skin.id}: secondary ${contrastRatio(skin.colors.textSecondary, card)}",
                contrastRatio(skin.colors.textSecondary, card) >= 4.5)
            assertTrue("${skin.id}: action ${contrastRatio(skin.colors.readableAccent, card)}",
                contrastRatio(skin.colors.readableAccent, card) >= 4.5)
        }
    }
}
