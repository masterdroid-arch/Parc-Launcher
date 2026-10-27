package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinEditorModelTest {
    @Test fun builtInStartsAsANamedCopy() {
        val m = SkinEditorModel.from(Skin.Midnight)
        assertNull(m.existingId)
        assertEquals("Midnight copy", m.name)
        assertFalse(m.isDirty)
        assertTrue(m.nameValid)
    }

    @Test fun customEditsInPlace() {
        val custom = Skin.Dusk.copy(id = "custom_dawn", name = "Dawn")
        val m = SkinEditorModel.from(custom)
        assertEquals("custom_dawn", m.existingId)
        assertEquals("custom_dawn", m.toSkin(setOf("custom_dawn")).id)
    }

    @Test fun everyTokenReadsAndWrites() {
        var m = SkinEditorModel.from(Skin.Midnight)
        for ((i, t) in ColorToken.entries.withIndex()) {
            val v = 0xFF000000L or (i * 0x101010L + 0x0A0B0CL)
            m = m.withColor(t, v)
            assertEquals(t.name, v, t.get(m.colors))
        }
        assertTrue(m.isDirty)
        assertEquals(Skin.Midnight.colors, m.reset().colors)
    }

    @Test fun lowContrastIsReported() {
        val m = SkinEditorModel.from(Skin.Midnight).withColor(ColorToken.TextPrimary, 0xFF101010L)
        assertTrue(m.failing().any { it.token == ColorToken.TextPrimary })
        val ok = SkinEditorModel.from(Skin.Midnight)
        assertTrue(ok.failing().none { it.token == ColorToken.TextPrimary })
    }

    @Test fun savingNeedsAValidUniqueName() {
        val m = SkinEditorModel.from(Skin.Midnight).withName("Night")
        assertTrue(m.canSave(listOf("Other")))
        assertFalse(m.canSave(listOf("night")))
        assertFalse(m.withName("   ").canSave(emptyList()))
        assertFalse(m.withName("x".repeat(17)).canSave(emptyList()))
    }

    @Test fun newSkinGetsAUniqueIdAndKeepsTheBaseStyle() {
        val base = Skin.Midnight
        val s = SkinEditorModel.from(base).withName("Night").toSkin(setOf("custom_night"))
        assertEquals("custom_night_2", s.id)
        assertEquals(base.style, s.style)
        assertEquals("Night", s.name)
    }

    // OPUS6 (audit F7): text the same colour as the background can be saved but not switched on.
    @Test fun unreadableTextCannotBeUsed() {
        val m = SkinEditorModel.from(Skin.Midnight)
        assertTrue(m.canUse())
        val bad = m.withColor(ColorToken.TextPrimary, Skin.Midnight.colors.bg)
        assertFalse(bad.canUse())
        assertTrue("still saveable so it can be fixed", bad.canSave(emptyList()))
    }
}
