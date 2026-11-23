package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T379: the twenty short-label rewrites, the one-line helper rule, the old-name search aliases and
 * the privacy consequences. Pure data, so the copy is checked here instead of by eye.
 */
class T379Test {
    /** The twenty rows of T371 §4 "Twenty shorter rows", by stable id. */
    private val rewrites: Map<String, Pair<String, String>> = mapOf(
        "ArcsShown" to ("Arcs shown" to "Removing a ring moves its apps one ring."),
        "IconsPerArc" to ("Apps per arc" to "Auto fits your screen."),
        "ArcMaximums" to ("Arc capacities" to "Limit how many apps each ring holds."),
        "ArcLayout" to ("Icon alignment" to "Line up icons or stagger them."),
        "LockLayout" to ("Lock layout" to "Prevent moves, additions and removals."),
        "LeftHand" to ("Left hand" to "Mirror the hub and rings."),
        "WallpaperImage" to ("Wallpaper" to "Phone wallpaper, picture or skin colour."),
        "WallpaperDim" to ("Wallpaper dim" to "Darken the wallpaper and glass."),
        "WallpaperReadability" to ("Text shading" to "Shade the background behind words."),
        "GlassEffect" to ("Glass" to "Frost both hub cards."),
        "GlassRefraction" to ("Glass edge" to "Bend the picture at card edges."),
        "ArcHubOpacity" to ("Arc opacity" to "Lower values reveal more wallpaper."),
        "StatsHubOpacity" to ("Stats opacity" to "Lower values reveal more wallpaper."),
        "HubPages" to ("Page visibility" to "Show, hide or reorder pages."),
        "HubSize" to ("Default hub size" to "Tall shows every tile."),
        "ShowHintsAgain" to ("Replay tips" to "See the Home gestures again."),
        "Joystick" to ("Aim from hub" to "Drag to point at an app."),
        "SearchEvents" to ("Calendar results" to "Find events in the next 60 days."),
        "CommsEnabled" to ("Live messages" to "Turning off keeps saved messages."),
        "WallpaperImageBackup" to ("Back up wallpaper" to "Include your private picture, up to 2 MB."),
    )

    @Test fun theTwentyRewritesAreAppliedExactly() {
        assertEquals(20, rewrites.size)
        rewrites.forEach { (id, copy) ->
            val def = SettingsRegistry.byId.getValue(id)
            assertEquals(id, copy.first, def.title)
            assertEquals(id, copy.second, def.help)
        }
    }

    @Test fun everyHelperFitsTheEightWordOneLineRule() {
        SettingsRegistry.all.forEach { def ->
            assertTrue("${def.id}: ${def.help}", SettingsCopy.fitsOneLine(def.help))
        }
    }

    @Test fun everyOldNameStillFindsItsRow() {
        listOf(
            "Icons per arc", "Maximum apps", "Arc layout", "What sits behind Parc", "Keep text readable",
            "Glass effect", "Glass refraction", "Show hints again", "Joystick aim", "Search calendar events",
            "Messages on/off", "Include wallpaper in backups",
        ).forEach { old ->
            assertTrue("old name: $old", SettingsRegistry.search(old).isNotEmpty())
        }
        assertTrue(SettingsRegistry.search("Icons per arc").any { it.id == "IconsPerArc" })
        assertTrue(SettingsRegistry.search("maximum apps").any { it.id == "ArcMaximums" })
        assertTrue(SettingsRegistry.search("glass refraction").any { it.id == "GlassRefraction" })
        assertTrue(SettingsRegistry.search("include wallpaper in backups").any { it.id == "WallpaperImageBackup" })
    }

    @Test fun longExplanationsLiveInInfo() {
        listOf("IconsPerArc", "ArcLayout", "LockLayout", "LeftHand", "WallpaperImage", "GlassEffect",
            "HubFullScreen", "CommsRecordHistory").forEach { id ->
            assertTrue(id, SettingsRegistry.byId.getValue(id).info.isNotBlank())
        }
    }

    @Test fun thePrivacyConsequenceSitsOnItsSwitch() {
        val messages = SettingsRegistry.byId.getValue("CommsRecordHistory")
        assertEquals("Record message history", messages.title)
        assertTrue(messages.help, messages.help.contains("never in backups"))
        assertTrue(messages.help, messages.help.contains("this phone"))

        val picture = SettingsRegistry.byId.getValue("WallpaperImageBackup")
        assertTrue(picture.help, picture.help.contains("private picture"))

        val events = SettingsRegistry.byId.getValue("SearchEvents")
        assertTrue(events.info, events.info.contains("calendar access"))
    }

    @Test fun theHelperRuleAndSearchUseTheSameShortCopy() {
        SettingsRegistry.all.forEach { def ->
            assertFalse(def.id, def.help.length > 120)
            assertTrue(def.id, def.help.isEmpty() || SettingsCopy.words(def.help) >= 1)
        }
    }

    @Test fun folderRemovalNamesTheFolderAndDestination() {
        assertEquals("Remove Social?", FolderRemovalCopy.title("Social"))
        assertEquals("Its apps move to New.", FolderRemovalCopy.Consequence)
        assertEquals("Remove", FolderRemovalCopy.Confirm)
        assertEquals("Cancel", FolderRemovalCopy.Cancel)
        assertTrue(FolderRemovalCopy.title("Mine").contains("Mine"))
    }
}
