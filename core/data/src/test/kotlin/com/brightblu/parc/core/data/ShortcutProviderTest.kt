package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutProviderTest {

    private val id = AppId("com.x/com.x.Main@0")

    @Test
    fun `empty when not the default home app`() {
        val source = FakeLauncherSource().apply {
            hasShortcutPermission = false
            shortcutsResult = listOf(SourceShortcut("s1", "One"))
        }

        assertTrue(ShortcutProvider(source).shortcuts(id).isEmpty())
        assertEquals(0, source.shortcutsCalls)
    }

    @Test
    fun `empty on SecurityException`() {
        val source = FakeLauncherSource().apply { shortcutsThrows = true }
        assertTrue(ShortcutProvider(source).shortcuts(id).isEmpty())
    }

    @Test
    fun `caps at five and binds the app id`() {
        val source = FakeLauncherSource().apply {
            shortcutsResult = (1..8).map { SourceShortcut("s$it", "Shortcut $it") }
        }

        val shortcuts = ShortcutProvider(source).shortcuts(id)
        assertEquals(5, shortcuts.size)
        assertEquals("s1", shortcuts.first().id)
        assertEquals(id, shortcuts.first().appId)
    }
}
