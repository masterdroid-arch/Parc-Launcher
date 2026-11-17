package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T230: nothing in a menu or a settings page may be cut off. The bottom of a page includes the whole
 * system bar and gesture area plus a gap, the right side keeps gesturenav's Back strip clear, and every
 * registered Settings page is routed through the one shared frame. Source guards cover the screens that
 * used to own their own inset handling.
 */
class T230Test {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found" }
    }

    private fun text(path: String): String = File(root(), path).readText()

    @Test fun theBottomPaddingIncludesNavigationGestureAndAGap() {
        // A device with no reported inset still keeps the 48 dp PROJECT.md safe zone (the 56 dp token).
        assertEquals(56f, ParcSpacing.pageBottomDp(0f, 0f), 0f)
        // Gesture navigation: the gesture area is used when it is the larger source.
        assertEquals(72f, ParcSpacing.pageBottomDp(24f, 48f), 0f)
        assertEquals(104f, ParcSpacing.pageBottomDp(0f, 80f), 0f)
        // Three-button navigation keeps its bar plus the section gap.
        assertEquals(72f, ParcSpacing.pageBottomDp(48f, 0f), 0f)
        assertEquals(124f, ParcSpacing.pageBottomDp(100f, 0f), 0f)
        assertTrue(ParcSpacing.pageBottomDp(48f, 48f) >= Metrics.keepOutBottomDp)
        assertTrue(ParcSpacing.pageBottomDp(48f, 48f) >= 48f + Metrics.sectionGapDp)
    }

    @Test fun theSidesKeepTheRightBackStripClear() {
        assertEquals(20f, ParcSpacing.pageStartDp(0f), 0f)
        assertEquals(48f, ParcSpacing.pageEndDp(0f), 0f)
        assertTrue(ParcSpacing.pageEndDp(0f) >= 40f) // PROJECT.md right safe zone
        assertEquals(80f, ParcSpacing.pageEndDp(60f), 0f)
        val page = ParcSpacing.page(statusTopDp = 46.9f, navBottomDp = 48f)
        assertEquals(48f, page.endDp, 0f)
        assertEquals(72f, page.bottomDp, 0f)
    }

    @Test fun everyRegisteredSettingsPageUsesTheSharedScaffold() {
        val source = text("app/src/main/kotlin/com/brightblu/parc/ParcSettings.kt")
        val start = source.indexOf("when (val page = shown.page)")
        val end = source.indexOf("previewSkin?.let", start)
        assertTrue("the Settings page dispatch was not found", start >= 0 && end > start)
        val dispatch = source.substring(start, end)
        val branches = Regex("""SettingsPage\.([A-Za-z]+)(?:\s*,\s*SettingsPage\.([A-Za-z]+))?\s*->""")
            .findAll(dispatch).toList()
        assertTrue("no Settings page branches were found", branches.size > 10)

        val scaffolds = listOf("SettingsPageFrame(", "SettingsRegistryPage(", "FolderEditorHost(",
            "FocusSettingsPage(", "GestureSettingsPage(")
        val covered = linkedSetOf<String>()
        branches.forEachIndexed { index, match ->
            val body = dispatch.substring(match.range.last + 1,
                branches.getOrNull(index + 1)?.range?.first ?: dispatch.length)
            match.groupValues.drop(1).filter { it.isNotBlank() }.forEach { name ->
                covered += name
                assertTrue("SettingsPage.$name bypasses the shared scaffold",
                    scaffolds.any { body.contains(it) })
            }
        }
        val nonRoot = SettingsPage.entries.filter { it != SettingsPage.Root }
        val elseCovered = nonRoot.filter { it.name !in covered }
        assertTrue("the dispatch relies on the registry fallback for most pages", elseCovered.size > 5)
        val fallback = dispatch.substringAfter("else ->", "")
        assertTrue("the registry fallback must use SettingsRegistryPage", fallback.contains("SettingsRegistryPage("))
        assertEquals(nonRoot.toSet(), (covered.map { SettingsPage.valueOf(it) } + elseCovered).toSet())

        // Root and the editor wrappers must all reach the same frame.
        val rootPage = source.substringAfter("internal fun RootPage")
        assertTrue(rootPage.contains("SettingsPageFrame("))
        listOf(
            "app/src/main/kotlin/com/brightblu/parc/FolderEditorHost.kt",
            "app/src/main/kotlin/com/brightblu/parc/FocusSettingsPage.kt",
            "app/src/main/kotlin/com/brightblu/parc/GestureSettings.kt",
        ).forEach { path -> assertTrue(path, text(path).contains("SettingsPageFrame(")) }
    }

    @Test fun theSharedPageReadsBottomAndRightSafeAreas() {
        val blocks = text("core/model/src/main/kotlin/com/brightblu/parc/model/ParcBlocks.kt")
        assertTrue(blocks.contains("WindowInsets.navigationBars"))
        assertTrue(blocks.contains("WindowInsets.systemGestures"))
        assertTrue(blocks.contains("WindowInsets.mandatorySystemGestures"))
        assertTrue(blocks.contains("fun ParcDialogArea("))
        assertTrue(blocks.contains("ParcSpacing.page("))
    }

    @Test fun pageOptionsAndLibrarySheetsUseTheSafeDialogArea() {
        val options = text("app/src/main/kotlin/com/brightblu/parc/HubPageOptions.kt")
        assertTrue(options.contains("ParcDialogArea"))
        assertTrue(options.contains("decorFitsSystemWindows = false"))
        assertFalse(options.contains(".systemBarsPadding()"))
        assertTrue(options.contains("verticalScroll(rememberScrollState()).parcBottomInset()"))

        val library = text("app/src/main/kotlin/com/brightblu/parc/LibraryPage.kt")
        assertTrue(library.contains("ParcDialogArea"))
        assertTrue(library.contains("decorFitsSystemWindows = false"))
        val selectionBar = library.substringAfter("private fun FindSelectionBar")
            .substringBefore("private fun DemoFindBooksDialog")
        assertFalse(selectionBar.contains("navigationBarsPadding"))
    }

    @Test fun menusSearchAndWhatsNewScrollClearOfTheBars() {
        val menu = text("app/src/main/kotlin/com/brightblu/parc/LongPressMenu.kt")
        assertTrue(menu.contains("rememberPageInsets()"))
        assertTrue(menu.contains("heightIn(max = maxHeightDp.dp)"))
        assertTrue(menu.contains("verticalScroll(rememberScrollState())"))

        val search = text("feature/search/src/main/kotlin/com/brightblu/parc/feature/search/SearchSheet.kt")
        assertTrue(search.contains("rememberPageInsets()"))
        assertTrue(search.contains("WindowInsets.ime"))
        assertTrue(search.contains("imePadding()"))

        val notes = text("app/src/main/kotlin/com/brightblu/parc/ReleaseNotesUi.kt")
        assertTrue(notes.contains("ParcDialogArea"))
        assertTrue(notes.contains("decorFitsSystemWindows = false"))
        assertTrue(notes.contains("verticalScroll(rememberScrollState())"))

        val folderEditor = text("app/src/main/kotlin/com/brightblu/parc/FolderEditorHost.kt")
        assertTrue(folderEditor.contains("padding(bottom = rememberPageInsets().bottomDp.dp)"))

        val hidden = text("feature/settings/src/main/kotlin/com/brightblu/parc/feature/settings/hidden/HiddenAppsScreen.kt")
        assertTrue(hidden.contains("ParcPage("))
        assertTrue(hidden.contains("LazyColumn("))
    }
}
