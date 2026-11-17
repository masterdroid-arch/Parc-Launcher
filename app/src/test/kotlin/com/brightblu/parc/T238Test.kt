package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.model.SkinJson
import com.brightblu.parc.model.contrastRatio
import com.brightblu.parc.model.readableAccent
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** T238: Year page colours: palette tokens, contrast on every shipped skin (Moss included), empty versus filled. */
class T238Test {
    private fun skins() = sequenceOf(File("../core/data/src/main/assets/skins"), File("core/data/src/main/assets/skins"))
        .first { it.isDirectory }.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { SkinJson.decode(it.readText()) }

    @Test fun colourIdsRoundTripAndUnknownIsAccent() {
        assertEquals(listOf("Accent", "Green", "Blue", "Amber", "Rose", "Mono"), YearColour.entries.map { it.label })
        YearColour.entries.forEach { assertEquals(it, YearColour.fromId(it.id)) }
        assertEquals(YearColour.Accent, YearColour.fromId(null))
        assertEquals(YearColour.Accent, YearColour.fromId("purple"))
    }

    @Test fun everyColourGivesFourDistinctOpaqueStepsGettingStrongerOnEverySkin() {
        val all = skins(); assertTrue(all.size >= 9)
        for (skin in all) for (colour in YearColour.entries) {
            val steps = YearPalette.steps(colour, skin.colors)
            assertEquals(4, steps.size)
            steps.forEach { assertEquals("${skin.id} ${colour.id} opaque", 0xFFL, it ushr 24) }
            val card = YearPalette.cards(skin.colors).first()
            val ratios = steps.map { contrastRatio(it, card) }
            for (i in 1 until 4) assertTrue("${skin.id} ${colour.id} step ${i + 1} must beat step $i: $ratios", ratios[i] > ratios[i - 1] + 0.1)
        }
    }

    @Test fun everyStepIsVisibleOnBothCardsOfEverySkinMossIncluded() {
        val bad = mutableListOf<String>()
        for (skin in skins()) for (colour in YearColour.entries) {
            val steps = YearPalette.steps(colour, skin.colors)
            for (card in YearPalette.cards(skin.colors)) {
                val worst = steps.minOf { contrastRatio(it, card) }
                if (worst < 3.0) bad += "${skin.id} ${colour.id} lowest step ${"%.2f".format(worst)} on ${card.toString(16)}"
            }
        }
        assertTrue(bad.joinToString("; "), bad.isEmpty())
    }

    @Test fun theEmptyRingIsReadableAndFilledStepsAreNotTheRingColour() {
        for (skin in skins()) {
            for (card in YearPalette.cards(skin.colors)) assertTrue(skin.id, contrastRatio(YearPalette.ring(skin.colors), card) >= 3.0)
        }
    }

    @Test fun accentFollowsTheSkinAndMonoUsesPrimaryText() {
        val moss = skins().first { it.id == "moss" }.colors
        assertEquals(moss.textPrimary, YearPalette.base(YearColour.Mono, moss))
        assertEquals(moss.readableAccent, YearPalette.base(YearColour.Accent, moss))
        val midnight = skins().first { it.id == "midnight" }.colors
        assertEquals(midnight.accent, YearPalette.base(YearColour.Accent, midnight))
    }

    @Test fun theColourChoiceIsInTheBackupAndShownInOptionsAndPage() {
        assertNotNull(BackupSpecs.hub.key("year_colour"))
        val page = File("src/main/kotlin/com/brightblu/parc/YearPage.kt").readText()
        assertTrue(page.contains("SettingsChoice(\"Colour\""))
        assertTrue(page.contains("YearLegend("))
        assertTrue(page.contains("\"less\"") && page.contains("\"more\""))
        // empty days: an outline ring; filled days: a solid circle
        assertTrue(page.contains("style = Stroke(thin)"))
        assertTrue(page.contains("drawCircle(filled, radius, centre)"))
    }
}
