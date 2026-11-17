package com.brightblu.parc

import com.brightblu.parc.arcade.ArcadeCatalog
import com.brightblu.parc.arcade.ArcadePage
import com.brightblu.parc.arcade.ArcadeState
import com.brightblu.parc.core.data.backup.BackupSpecs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T200: the hidden arcade is invisible until a game is unlocked, then an ordinary hub page. */
class T200Test {
    private val id = ArcadePage.Id

    @Test fun theArcadeIsInTheCatalogButNeverHiddenByDefault() {
        assertEquals("arcade", id)
        assertTrue(id in HubCatalog.pages)
        assertTrue(id in HubCatalog.tilelessPages)
        assertFalse(id in HubCatalog.defaultHiddenPages)
        assertFalse(id in HubCatalog.newUserHiddenPages)
        assertEquals(id, HubCatalog.pages.last())
        assertNotNull(hubPageOptions[id])
        assertNotNull(hubTemplates[id])
        assertEquals("Arcade", hubPageName(id))
    }

    @Test fun lockedMeansNoTabNoSettingsRowAndNoSavedHint() {
        val locked = HubConfig()
        assertFalse(id in locked.pages)
        assertFalse(id in locked.orderedPages)
        // An old saved config meets the page and still shows nothing.
        val old = HubConfig.decode("clock:true::;system:true:battery:;network:true:traffic:")
        assertFalse(id in old.pages)
        assertFalse(id in old.orderedPages)
        // Moving and hiding other pages while locked never reveal it.
        assertFalse(id in locked.movePage("power", 1).orderedPages)
        assertFalse(id in locked.showPage("power", false).pages)
    }

    @Test fun unlockedItBehavesLikeAnyPage() {
        val open = HubConfig(arcadeOpen = true)
        assertTrue(id in open.pages)
        assertEquals(id, open.orderedPages.last())
        // It can be hidden, shown and moved.
        assertFalse(id in open.showPage(id, false).pages)
        assertTrue(id in open.showPage(id, false).showPage(id, true).pages)
        assertEquals(0, open.movePageTo(id, 0).orderedPages.indexOf(id))
        // A config saved while locked gains it visible once a game is unlocked; one saved while unlocked remembers its place and hidden flag.
        val saved = HubConfig.decode(HubConfig().encode())
        assertTrue(id in saved.copy(arcadeOpen = true).pages)
        val customised = open.movePageTo(id, 1).showPage(id, false)
        val reloaded = HubConfig.decode(customised.encode()).copy(arcadeOpen = true)
        assertEquals(1, reloaded.orderedPages.indexOf(id))
        assertFalse(id in reloaded.pages)
        // Saving while locked does not forget the arcade row.
        assertTrue(customised.copy(arcadeOpen = false).encode().contains("$id:"))
    }

    @Test fun anOldConfigGainsTheArcadeVisibleAtTheEnd() {
        val old = HubConfig.decode("clock:true::;system:true:battery:;network:true:traffic:").copy(arcadeOpen = true)
        assertTrue(id in old.pages)
        assertEquals(id, old.orderedPages.last())
    }

    @Test fun theDemoListDoesNotHoldItUntilUnlocked() {
        assertFalse(id in DemoHubPages)
        assertFalse(id in hubPagesFor(true, emptyList()))
        assertTrue(id in hubPagesFor(true, emptyList(), arcadeOpen = true))
        assertEquals(DemoHubPages, hubPagesFor(true, emptyList()))
        assertEquals(listOf("clock"), hubPagesFor(false, listOf("clock", id)) { it != id })
    }

    @Test fun theArcadeStateIsInTheHubBackupSection() {
        val keys = BackupSpecs.hub.keys.map { it.name }
        assertTrue(ArcadeState.GamesKey in keys)
        assertTrue(ArcadeState.BestKey in keys)
        assertTrue(ArcadeState.SinceKey in keys)
        // A game id list and a best-score list fit their limits.
        val max = BackupSpecs.hub.keys.first { it.name == ArcadeState.GamesKey }.maxLength
        assertTrue(max >= 32 * 3)
    }

    @Test fun everyRegisteredGameHasATitleTheTileCanShow() {
        assertTrue(ArcadeCatalog.games.isNotEmpty())
        ArcadeCatalog.games.forEach { assertTrue(it.title.length in 1..24) }
    }

    @Test fun nothingPublicNamesTheArcadeOrItsGames() {
        // Release notes (in app) and the README must not mention it.
        val notes = File("src/main/assets/changelog.json")
        if (notes.exists()) {
            val text = notes.readText().lowercase()
            assertFalse(text.contains("arcade")); assertFalse(text.contains("orbit"))
        }
        val readme = File("../README.md")
        if (readme.exists()) {
            val text = readme.readText().lowercase()
            // (The picture file "arc-orbit.svg" is about the arcs, not the game.)
            assertFalse(text.contains("arcade")); assertFalse(Regex("(?<![-\\w])orbit(?![-\\w])").containsMatchIn(text))
        }
    }
}
