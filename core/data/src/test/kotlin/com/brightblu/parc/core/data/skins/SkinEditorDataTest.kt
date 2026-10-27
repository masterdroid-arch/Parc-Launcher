package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkinEditorDataTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hexParsesAllForms() {
        assertEquals(0xFF112233L, ColorMath.parseHex("#112233"))
        assertEquals(0x80112233L, ColorMath.parseHex("80112233"))
        assertEquals(0xFFAABBCCL, ColorMath.parseHex("#abc"))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("#GGGGGG"))
        assertNull(ColorMath.parseHex(""))
    }

    @Test fun hexRoundTrips() {
        assertEquals("#7FB4C8", ColorMath.formatHex(0xFF7FB4C8L))
        assertEquals("#477FB4C8", ColorMath.formatHex(0x477FB4C8L))
        assertEquals(0x477FB4C8L, ColorMath.parseHex(ColorMath.formatHex(0x477FB4C8L)))
    }

    @Test fun hsvRoundTripsWithinOneStep() {
        for (c in listOf(0xFF7FB4C8L, 0xFFE5736BL, 0xFF000000L, 0xFFFFFFFFL, 0xFFB79CDBL)) {
            val back = ColorMath.fromHsv(ColorMath.toHsv(c))
            for (shift in listOf(16, 8, 0)) {
                val d = Math.abs(((c ushr shift) and 0xFF) - ((back ushr shift) and 0xFF))
                assertTrue("channel off by $d for ${ColorMath.formatHex(c)}", d <= 1)
            }
        }
    }

    @Test fun contrastMatchesWcagReferences() {
        assertEquals(21.0, ColorMath.contrast(0xFFFFFFFFL, 0xFF000000L), 0.01)
        assertEquals(1.0, ColorMath.contrast(0xFF777777L, 0xFF777777L), 0.001)
        // #767676 on white is the well-known 4.54:1 pass.
        assertEquals(4.54, ColorMath.contrast(0xFF767676L, 0xFFFFFFFFL), 0.02)
        assertTrue(ColorMath.contrast(Skin.Midnight.colors.textPrimary, Skin.Midnight.colors.bg) >= ColorMath.TextContrast)
    }

    @Test fun translucentForegroundIsCompositedBeforeContrast() {
        val half = 0x80FFFFFFL
        val c = ColorMath.contrast(half, 0xFF000000L)
        assertTrue(c in 5.0..6.0)
    }

    @Test fun namingRules() {
        assertTrue(SkinNaming.isValidName("  My night  "))
        assertFalse(SkinNaming.isValidName("   "))
        assertFalse(SkinNaming.isValidName("x".repeat(17)))
        assertEquals("custom_my_night", SkinNaming.uniqueId("My Night!", emptySet()))
        assertEquals("custom_my_night_3", SkinNaming.uniqueId("my night", setOf("custom_my_night", "custom_my_night_2")))
        assertEquals("custom_skin", SkinNaming.uniqueId("!!!", emptySet()))
        assertFalse(SkinNaming.isCustomId("midnight"))
    }

    @Test fun exportThenImportRoundTripsAsNewCustomSkin() {
        val original = Skin.Dusk.copy(id = "custom_dawn", name = "Dawn")
        val text = SkinExchange.export(original)
        val r = SkinExchange.import(text, Skin.Midnight, setOf("midnight", "dusk", "custom_dawn"))
        assertTrue(r is SkinImportResult.Imported)
        val skin = (r as SkinImportResult.Imported).skin
        assertEquals("custom_dawn_2", skin.id)
        assertEquals(original.colors, skin.colors)
        assertEquals("Dawn", skin.name)
    }

    @Test fun importNeverTakesABuiltInId() {
        val text = SkinExchange.export(Skin.Dusk) // id "dusk", a built-in id
        val skin = (SkinExchange.import(text, Skin.Midnight, setOf("midnight", "dusk")) as SkinImportResult.Imported).skin
        assertTrue(SkinNaming.isCustomId(skin.id))
    }

    @Test fun importAcceptsHexStringsFillsMissingAndIgnoresUnknown() {
        val text = """{"name":"Mini","colors":{"accent":"#FF0000","bogus":"#123456"},"junk":1}"""
        val r = SkinExchange.import(text, Skin.Midnight, emptySet()) as SkinImportResult.Imported
        assertEquals(0xFFFF0000L, r.skin.colors.accent)
        assertEquals(Skin.Midnight.colors.bg, r.skin.colors.bg)
        assertTrue(r.notes.any { "junk" in it })
        assertTrue(r.notes.any { "bogus" in it })
    }

    @Test fun importRejectsBadInput() {
        fun rej(t: String) = SkinExchange.import(t, Skin.Midnight, emptySet()) is SkinImportResult.Rejected
        assertTrue(rej("not json"))
        assertTrue(rej("[1,2]"))
        assertTrue(rej("""{"colors":{"accent":"#12"}}"""))
        assertTrue(rej("""{"colors":5}"""))
        assertTrue(rej("""{"motionScale": 9.0}"""))
        assertTrue(rej("x".repeat(SkinExchange.MaxBytes + 1)))
    }

    @Test fun importTruncatesLongNames() {
        val r = SkinExchange.import("""{"name":"${"n".repeat(40)}"}""", Skin.Midnight, emptySet()) as SkinImportResult.Imported
        assertEquals(SkinNaming.MaxNameLength, r.skin.name.length)
    }

    @Test fun storeSavesListsAndDeletes() {
        val store = CustomSkinStore(tmp.newFolder("skins"))
        assertTrue(store.skins.value.isEmpty())
        val a = Skin.Midnight.copy(id = "custom_b", name = "Bravo")
        val b = Skin.Dusk.copy(id = "custom_a", name = "Alpha")
        assertTrue(store.save(a)); assertTrue(store.save(b))
        assertEquals(listOf("Alpha", "Bravo"), store.skins.value.map { it.name })
        assertTrue(store.save(a.copy(name = "Bravo 2")))
        assertEquals(2, store.skins.value.size)
        assertTrue(store.delete("custom_b"))
        assertEquals(listOf("custom_a"), store.skins.value.map { it.id })
    }

    @Test fun storeRefusesBuiltInIdsAndBadNames() {
        val store = CustomSkinStore(tmp.newFolder("skins2"))
        assertFalse(store.save(Skin.Midnight))
        assertFalse(store.save(Skin.Midnight.copy(id = "custom_x", name = "  ")))
        assertFalse(store.delete("midnight"))
    }

    @Test fun storeIgnoresCorruptAndMismatchedFiles() {
        val dir = tmp.newFolder("skins3")
        java.io.File(dir, "custom_bad.json").writeText("{ nope")
        java.io.File(dir, "custom_wrong.json").writeText(com.brightblu.parc.model.SkinJson.encode(Skin.Midnight.copy(id = "custom_other", name = "Other")))
        java.io.File(dir, "readme.txt").writeText("hi")
        val store = CustomSkinStore(dir)
        assertTrue(store.skins.value.isEmpty())
        assertNotNull(store)
    }
}
