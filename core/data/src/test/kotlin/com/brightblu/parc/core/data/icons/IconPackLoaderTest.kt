package com.brightblu.parc.core.data.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IconPackLoaderTest {

    private class FakeIcon(val bytes: Int)

    private class FakeSource : IconPackSource<FakeIcon> {
        val filters = mutableMapOf<String, Map<String, String>>()
        var filterCalls = 0
            private set
        var drawableCalls = 0
            private set
        var filterFailure: RuntimeException? = null
        var drawableFailure: RuntimeException? = null
        /** Set to override the default "an icon of sizePx squared bytes"; may return null. */
        var drawableOverride: ((String, String, Int) -> FakeIcon?)? = null

        override fun packs(): List<IconPack> = emptyList()

        override fun appFilter(packageName: String): Map<String, String> {
            filterCalls++
            filterFailure?.let { throw it }
            return filters[packageName] ?: emptyMap()
        }

        override fun drawable(packageName: String, drawableName: String, sizePx: Int): FakeIcon? {
            drawableCalls++
            drawableFailure?.let { throw it }
            drawableOverride?.let { return it(packageName, drawableName, sizePx) }
            return FakeIcon(sizePx * sizePx)
        }
    }

    private val component = "com.example/com.example.Main"

    private fun loader(
        source: FakeSource,
        fallback: (String, Int) -> FakeIcon? = { _, size -> FakeIcon(size) },
        log: MutableList<String> = mutableListOf(),
        maxBytes: Int = 1_000_000,
    ) = IconPackLoader(
        source = source,
        sizeOf = { it.bytes },
        fallback = fallback,
        logWarn = { log.add(it) },
        maxBytes = maxBytes,
    )

    @Test
    fun `no pack selected uses the app icon and never reads a pack`() {
        val source = FakeSource()
        val icon = loader(source).iconFor(component, 112, null)

        assertEquals(112, icon?.bytes)
        assertEquals(0, source.filterCalls)
        assertEquals(0, source.drawableCalls)
    }

    @Test
    fun `blank selection is none`() {
        val source = FakeSource()
        val icon = loader(source).iconFor(component, 48, "   ")

        assertEquals(48, icon?.bytes)
        assertEquals(0, source.filterCalls)
    }

    @Test
    fun `a mapped component uses the pack drawable`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf("com.example/com.example.main" to "themed")
            drawableOverride = { pack, name, size ->
                if (pack == "pack.one" && name == "themed" && size == 96) FakeIcon(1) else null
            }
        }

        val icon = loader(source).iconFor(component.uppercase(), 96, "pack.one")

        assertEquals(1, icon?.bytes)
        assertEquals(1, source.drawableCalls)
    }

    @Test
    fun `a component the pack does not map uses the app icon`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf("other/other.Main" to "themed")
        }
        var fallbacks = 0
        val icon = loader(source, fallback = { _, size -> fallbacks++; FakeIcon(size) })
            .iconFor(component, 112, "pack.one")

        assertEquals(112, icon?.bytes)
        assertEquals(1, fallbacks)
        assertEquals(0, source.drawableCalls)
    }

    @Test
    fun `a missing pack drawable uses the app icon`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf("com.example/com.example.main" to "gone")
            drawableOverride = { _, _, _ -> null }
        }

        val icon = loader(source).iconFor(component, 72, "pack.one")

        assertEquals(72, icon?.bytes)
        assertEquals(1, source.drawableCalls)
    }

    @Test
    fun `an unreadable filter or drawable falls back and is logged without identifiers`() {
        val log = mutableListOf<String>()
        val brokenFilter = FakeSource().apply { filterFailure = IllegalStateException("boom") }
        assertEquals(112, loader(brokenFilter, log = log).iconFor(component, 112, "pack.one")?.bytes)

        val brokenDrawable = FakeSource().apply {
            filters["pack.one"] = mapOf("com.example/com.example.main" to "themed")
            drawableFailure = IllegalStateException("boom")
        }
        assertEquals(112, loader(brokenDrawable, log = log).iconFor(component, 112, "pack.one")?.bytes)

        assertEquals(
            listOf("icon pack filter unreadable", "icon pack drawable unreadable"),
            log,
        )
    }

    @Test
    fun `a failing fallback yields null so the letter tile takes over`() {
        val source = FakeSource()
        val icon = loader(source, fallback = { _, _ -> throw IllegalStateException("no icon") })
            .iconFor(component, 112, null)

        assertNull(icon)
    }

    @Test
    fun `pack bitmaps are cached per component and size`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf("com.example/com.example.main" to "themed")
        }
        val loader = loader(source)

        val first = loader.iconFor(component, 112, "pack.one")
        val second = loader.iconFor(component.uppercase(), 112, "pack.one")
        assertEquals(1, source.drawableCalls)
        assertSame(first, second)

        loader.iconFor(component, 48, "pack.one")
        assertEquals(2, source.drawableCalls)
    }

    @Test
    fun `the filter is read once per pack until cleared`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf("com.example/com.example.main" to "themed")
        }
        val loader = loader(source)

        loader.iconFor(component, 112, "pack.one")
        loader.iconFor(component, 96, "pack.one")
        assertEquals(1, source.filterCalls)

        loader.clear()
        loader.iconFor(component, 112, "pack.one")
        assertEquals(2, source.filterCalls)
        assertEquals(3, source.drawableCalls)
    }

    @Test
    fun `an empty filter is cached and does not repeat the read`() {
        val source = FakeSource()
        val loader = loader(source)

        loader.iconFor(component, 112, "pack.one")
        loader.iconFor(component, 96, "pack.one")

        assertEquals(1, source.filterCalls)
    }

    @Test
    fun `the bitmap cache is bounded by bytes`() {
        val source = FakeSource().apply {
            filters["pack.one"] = mapOf(
                "a/a.one" to "one",
                "a/a.two" to "two",
                "a/a.three" to "three",
            )
        }
        val loader = loader(source, maxBytes = 2)

        loader.iconFor("a/a.One", 1, "pack.one")
        loader.iconFor("a/a.Two", 1, "pack.one")
        loader.iconFor("a/a.Three", 1, "pack.one")

        assertEquals(2, loader.cachedBytes())
    }
}
