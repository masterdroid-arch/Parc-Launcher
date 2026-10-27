package com.brightblu.parc.core.data.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterParserTest {

    private val pack = """
        <?xml version="1.0" encoding="UTF-8"?>
        <resources>
            <!-- three component formats appear in real packs -->
            <item component="ComponentInfo{com.example.one/com.example.one.MainActivity}" drawable="pack_one" />
            <item component="com.example.two/.Second" drawable="pack_two"/>
            <item component="ComponentInfo{com.example.three/com.example.three.MainActivity{LauncherAlias}}" drawable='pack_three'/>
        </resources>
    """.trimIndent()

    @Test
    fun `parses the component formats found in real packs`() {
        val map = AppFilterParser.parseXml(pack)

        assertEquals(3, map.size)
        assertEquals("pack_one", map["com.example.one/com.example.one.mainactivity"])
        assertEquals("pack_two", map["com.example.two/com.example.two.second"])
        assertEquals("pack_three", map["com.example.three/com.example.three.mainactivity"])
    }

    @Test
    fun `component keys are case-insensitive`() {
        val map = AppFilterParser.parseXml(
            "<resources><item component=\"ComponentInfo{Com.Example/Com.Example.Main}\" drawable=\"x\"/></resources>",
        )

        assertEquals("x", map["com.example/com.example.main"])
    }

    @Test
    fun `later entries for one component win`() {
        val map = AppFilterParser.parseXml(
            """
            <resources>
                <item component="a/a.Main" drawable="first"/>
                <item component="ComponentInfo{a/a.Main}" drawable="second"/>
            </resources>
            """.trimIndent(),
        )

        assertEquals(mapOf("a/a.main" to "second"), map)
    }

    @Test
    fun `entries without a usable component or drawable are skipped`() {
        val map = AppFilterParser.parseXml(
            """
            <resources>
                <item drawable="no_component"/>
                <item component="a/a.Main"/>
                <item component="a/a.Second" drawable="   "/>
                <item component="" drawable="empty"/>
                <item component="not-a-component" drawable="bad"/>
                <item component="a/a.Third" drawable="ok"/>
            </resources>
            """.trimIndent(),
        )

        assertEquals(mapOf("a/a.third" to "ok"), map)
    }

    @Test
    fun `only item tags contribute`() {
        val map = AppFilterParser.parseXml(
            """
            <resources>
                <style component="a/a.Main" drawable="nope"/>
                <item component="a/a.Main" drawable="yes"/>
            </resources>
            """.trimIndent(),
        )

        assertEquals(mapOf("a/a.main" to "yes"), map)
    }

    @Test
    fun `quoted angle brackets do not end a tag`() {
        val map = AppFilterParser.parseXml(
            "<resources><item component=\"a/a.Main\" drawable=\"odd>name\"/></resources>",
        )

        assertEquals("odd>name", map["a/a.main"])
    }

    @Test
    fun `entities in attribute values are decoded`() {
        val map = AppFilterParser.parseXml(
            "<resources><item component=\"a/a.Main\" drawable=\"rock&amp;roll\"/></resources>",
        )

        assertEquals("rock&roll", map["a/a.main"])
    }

    @Test
    fun `an unterminated tag ends parsing without throwing`() {
        val map = AppFilterParser.parseXml(
            "<resources><item component=\"a/a.Main\" drawable=\"kept\"/><item component=",
        )

        assertEquals(mapOf("a/a.main" to "kept"), map)
    }

    @Test
    fun `comments and processing instructions are skipped`() {
        val tags = AppFilterParser.tagsFromXml(
            "<?xml version=\"1.0\"?><!-- <item component=\"x/x.Y\" drawable=\"no\"/> -->\n" +
                "<resources><item component=\"x/x.Y\" drawable=\"yes\"/></resources>",
        )

        assertEquals(listOf("resources", "item"), tags.map { it.name })
        assertEquals("yes", AppFilterParser.parse(tags)["x/x.y"])
    }

    @Test
    fun `normalizeComponent rejects values that cannot name an activity`() {
        assertNull(AppFilterParser.normalizeComponent(""))
        assertNull(AppFilterParser.normalizeComponent("   "))
        assertNull(AppFilterParser.normalizeComponent("ComponentInfo{}"))
        assertNull(AppFilterParser.normalizeComponent("ComponentInfo"))
        assertNull(AppFilterParser.normalizeComponent("package"))
        assertNull(AppFilterParser.normalizeComponent("package/"))
        assertNull(AppFilterParser.normalizeComponent("/class"))
    }

    @Test
    fun `normalizeComponent keeps the package and class only`() {
        assertEquals(
            "com.example/com.example.main",
            AppFilterParser.normalizeComponent("  ComponentInfo{com.example/com.example.Main}  "),
        )
        assertEquals(
            "com.example/com.example.main",
            AppFilterParser.normalizeComponent("ComponentInfo{com.example/com.example.Main{Alias}}"),
        )
        assertEquals(
            "com.example/com.example.main",
            AppFilterParser.normalizeComponent("com.example/.Main"),
        )
    }

    @Test
    fun `an empty document parses to an empty map`() {
        assertTrue(AppFilterParser.parseXml("").isEmpty())
        assertTrue(AppFilterParser.parseXml("<resources/>").isEmpty())
    }
}
