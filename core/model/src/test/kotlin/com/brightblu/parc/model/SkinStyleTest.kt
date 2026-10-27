package com.brightblu.parc.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class SkinStyleTest {
    @Test fun oldJsonHasIdenticalDefaultPresentation() {
        val source = """{"id":"old","name":"Old","colors":{"bg":4278190080,"lineBase":4278190080,"lineActive":4278190080,"accent":4294967295,"glow":0,"textPrimary":4294967295,"textSecondary":4294967295,"pulse":4294967295,"danger":4294967295,"scrim":0,"surface":4278190080}}"""
        val skin = SkinJson.decode(source)
        assertEquals(SkinStyle(), skin.style)
        assertEquals(0f, skin.drawingSpec().dashOnDp)
        assertEquals(IconFrame.disc, skin.drawingSpec().iconFrame)
        assertEquals(HubStyle.plain, skin.drawingSpec().hub)
        assertEquals(skin.motion, skin.effectiveMotion)
    }

    @Test fun stylesRoundTripIncludingEveryOptionAndMotion() {
        for (arc in ArcStyle.entries) for (background in BackgroundPattern.entries) {
            val style = FieldAtlas.Style.copy(arc = arc, background = background,
                motion = MotionTokens(durSnap = 240, durShort = 120))
            val skin = FieldAtlas.Dark.copy(style = style)
            assertEquals(skin, SkinJson.decode(SkinJson.encode(skin)))
            assertEquals(240, skin.effectiveMotion.durSnap)
        }
    }

    @Test fun partialStyleFillsDefaults() {
        val source = SkinJson.encode(Skin.Midnight).replace("\"arc\":\"solid\"", "\"arc\":\"dotted\"")
        val parsed = SkinJson.decode(source)
        assertEquals(ArcStyle.dotted, parsed.style.arc)
        assertEquals(HubStyle.plain, parsed.style.hub)
        val minimal = SkinJson.encode(Skin.Midnight).substringBefore("\"style\":") + "\"style\":{\"arc\":\"dashed\"}}"
        assertEquals(SkinStyle(arc = ArcStyle.dashed), SkinJson.decode(minimal).style)
    }

    @Test fun rejectsInvalidStyleNumbersAndEnums() {
        listOf<() -> Unit>(
            { SkinStyle(iconScale = Float.NaN) }, { SkinStyle(iconScale = 0.2f) },
            { SkinStyle(iconOutlineDp = -1f) }, { SkinStyle(iconOutlineDp = 4f) },
            { SkinStyle(clockWeight = 999) }, { SkinStyle(clockFamily = "remote") },
            { SkinStyle(motion = MotionTokens(durSnap = -1)) },
            { SkinJson.decode(SkinJson.encode(Skin.Midnight).replace("\"solid\"", "\"invalid\"")) },
        ).forEach { action ->
            try { action(); fail("Invalid style accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun mappingCoversAllPresentations() {
        assertEquals(0f, Skin.Midnight.drawingSpec().dashOnDp)
        assertTrue(Skin.Midnight.copy(style = SkinStyle(arc = ArcStyle.dashed)).drawingSpec().dashOnDp > 0f)
        assertTrue(Skin.Midnight.copy(style = SkinStyle(arc = ArcStyle.dotted)).drawingSpec().dashOffDp > 0f)
        assertTrue(Skin.Midnight.copy(style = SkinStyle(arc = ArcStyle.contour)).drawingSpec().ticks)
        for (skin in listOf(FieldAtlas.Light, FieldAtlas.Dark)) {
            val spec = skin.drawingSpec()
            assertTrue(spec.ticks && spec.bearings)
            assertEquals(IconFrame.registration, spec.iconFrame)
            assertEquals(HubStyle.compass, spec.hub)
            assertEquals(AimStyle.distance, spec.aim)
            assertEquals(LabelStyle.mapLabel, spec.label)
            assertEquals(BackgroundPattern.contour, spec.background)
            assertEquals(StatsFrame.legend, spec.statsFrame)
            assertEquals(HeaderStyle.ruled, spec.header)
            assertEquals("serif", spec.clockFamily)
            assertEquals(400, spec.clockWeight)
            assertEquals("monospace", spec.annotationFamily)
        }
        for (frame in IconFrame.entries) assertEquals(frame, Skin.Midnight.copy(style = SkinStyle(iconFrame = frame)).drawingSpec().iconFrame)
        for (hub in HubStyle.entries) assertEquals(hub, Skin.Midnight.copy(style = SkinStyle(hub = hub)).drawingSpec().hub)
        for (aim in AimStyle.entries) assertEquals(aim, Skin.Midnight.copy(style = SkinStyle(aim = aim)).drawingSpec().aim)
        for (label in LabelStyle.entries) assertEquals(label, Skin.Midnight.copy(style = SkinStyle(label = label)).drawingSpec().label)
        for (frame in StatsFrame.entries) assertEquals(frame, Skin.Midnight.copy(style = SkinStyle(statsFrame = frame)).drawingSpec().statsFrame)
    }

    @Test fun allAtlasTextRolesMeetContrastOnBothBackgrounds() {
        for (skin in listOf(FieldAtlas.Light, FieldAtlas.Dark, Phosphor.Skin)) {
            for (background in listOf(skin.colors.bg, skin.colors.surface)) {
                for (text in listOf(skin.colors.textPrimary, skin.colors.textSecondary, skin.colors.accent, skin.colors.danger, skin.colors.warn)) {
                    val a = luminance(text); val b = luminance(background)
                    val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                    assertTrue("${skin.id}: $text on $background: $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun phosphorMapsToTerminalDrawingWithoutChangingGeometry() {
        val skin = Phosphor.Skin
        val spec = skin.drawingSpec()
        assertEquals(skin, SkinJson.decode(SkinJson.encode(skin)))
        assertTrue(spec.dashOnDp > 0f && spec.dashOffDp > spec.dashOnDp)
        assertTrue(spec.ticks)
        assertEquals(IconFrame.brackets, spec.iconFrame)
        assertEquals(LabelStyle.brackets, spec.label)
        assertEquals(HubStyle.crosshair, spec.hub)
        assertEquals(AimStyle.coordinates, spec.aim)
        assertEquals(StatsFrame.terminal, spec.statsFrame)
        assertEquals("monospace", spec.clockFamily)
        assertEquals("monospace", spec.annotationFamily)
        assertEquals("monospace", skin.typography.fallbackFamily)
        assertEquals(BackgroundPattern.none, spec.background)
        assertEquals(Skin.Midnight.effectiveMotion, skin.effectiveMotion)
        assertEquals(Skin.Midnight.depthScale, skin.depthScale)
        assertEquals(0L, skin.colors.glow ushr 24)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 255).toDouble() / 255
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
