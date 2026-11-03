package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.backup.PreferencesStoreAccess
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T175: the wiring test over the WHOLE registry. Every setting with a value is changed to every other option; the snapshot
 * must hold it (read back equals what was written), the store that keeps it must be one the writer handles and a backup
 * carries, a visual setting must change what Home draws, and a hidden row must never be able to change the screen.
 */
class SettingsRegistryTest {
    private val skins = File("../core/data/src/main/assets/skins").listFiles()!!.filter { it.extension == "json" }
        .map { SkinJson.decode(it.readText()) }.sortedBy { it.id }
    private val glassSkin = skins.first { it.id == "glass" }

    /** The contexts every rule is checked in: each background, glass on and off, the stats hub on and off, clock on and off. */
    private val bases: List<SettingsSnapshot> = buildList {
        for (wallpaper in WallpaperStyle.entries) for (glass in listOf(false, true)) for (stats in listOf(true, false)) {
            val base = SettingsSnapshot(skins = skins, skinId = "midnight", look = LookSettings(wallpaper = wallpaper),
                hub = HubAppearance(glass = glass), statsVisible = stats, iconPacks = listOf("pack.one" to "Pack one"))
            add(base)
            if (!stats) add(base.copy(clockVisible = false, badges = false, menuNotifications = false,
                feel = base.feel.copy(sound = com.brightblu.parc.ring.SoundTheme.Off), haptics = com.brightblu.parc.core.data.HapticsLevel.Off))
        }
        add(SettingsSnapshot(skins = skins, skinId = "glass", look = LookSettings(wallpaper = WallpaperStyle.Image)))
    }

    /** Every snapshot field a registry row may change, and so one SettingsWriter.persist must save. */
    private val fields: List<Pair<String, (SettingsSnapshot) -> Any?>> = listOf(
        "look" to { s -> s.look }, "hub" to { s -> s.hub }, "skinId" to { s -> s.skinId }, "counts" to { s -> s.counts },
        "aligned" to { s -> s.aligned }, "joystick" to { s -> s.joystick }, "hubDoubleTap" to { s -> s.hubDoubleTap },
        "haptics" to { s -> s.haptics }, "feel" to { s -> s.feel }, "arcFeel" to { s -> s.arcFeel }, "clockVisible" to { s -> s.clockVisible },
        "iconPack" to { s -> s.iconPack }, "statsVisible" to { s -> s.statsVisible }, "hubSize" to { s -> s.hubSize },
        "searchPhoneSettings" to { s -> s.searchPhoneSettings }, "searchFiles" to { s -> s.searchFiles },
        "searchEvents" to { s -> s.searchEvents }, "badges" to { s -> s.badges }, "badgesInAllApps" to { s -> s.badgesInAllApps },
        "menuNotifications" to { s -> s.menuNotifications }, "menuNotificationText" to { s -> s.menuNotificationText },
        "markWorkApps" to { s -> s.markWorkApps }, "wallpaperMode" to { s -> s.wallpaperMode }, "wallpaperLook" to { s -> s.wallpaperLook },
    )

    /** Where each value lives in a backup: store id and key. The keys are the stores' own, unchanged by T175. */
    private val backupKey: Map<String, Pair<String, String>> = mapOf(
        "look" to ("look" to "look_v1"), "hub" to ("hub" to "arc_hub_colour"), "skinId" to ("settings" to "skin_id"),
        "counts" to ("ui_prefs" to "arcs_shown"), "aligned" to ("ui_prefs" to "align_across_arcs"), "joystick" to ("ui_prefs" to "joystick_aim"),
        "hubDoubleTap" to ("ui_prefs" to "hub_double_tap"), "haptics" to ("settings" to "haptics"), "feel" to ("feel" to "haptic_preset"),
        "arcFeel" to ("feel" to "arc_spin_limit"), "clockVisible" to ("settings" to "clock_visible"), "iconPack" to ("icon_pack" to "selected_pack"),
        "statsVisible" to ("settings" to "stats_visible"), "hubSize" to ("hub" to "hub_size"),
        "searchPhoneSettings" to ("search" to "source_settings"), "searchFiles" to ("search" to "source_files"),
        "searchEvents" to ("search" to "source_events"), "badges" to ("ui_prefs" to "notification_badges"),
        "badgesInAllApps" to ("ui_prefs" to "badges_in_all_apps"), "menuNotifications" to ("ui_prefs" to "menu_notifications"),
        "menuNotificationText" to ("ui_prefs" to "menu_notification_text"), "markWorkApps" to ("ui_prefs" to "mark_work_apps"),
        "wallpaperMode" to ("settings" to "wallpaper_skin_mode"), "wallpaperLook" to ("settings" to "wallpaper_skin_look"),
    )

    private fun changedFields(a: SettingsSnapshot, b: SettingsSnapshot) = fields.filter { (_, get) -> get(a) != get(b) }.map { it.first }

    @Test fun idsAreUniqueAndEveryPageHasRows() {
        val ids = SettingsRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        SettingsHome.entries.forEach { assertTrue(it.title, SettingsRegistry.on(it).isNotEmpty()) }
        assertEquals(listOf("Look", "Hubs", "Layout", "Gestures and feel", "Stats hub pages", "Search and notifications", "Backup and about"),
            SettingsHome.entries.map { it.title })
    }

    @Test fun pageRowsOpenAnEditorAndEveryEditorIsReachable() {
        SettingsRegistry.all.filter { it.control == SettingControl.Page }.forEach { def ->
            assertNotNull(def.id, def.opens); assertNull(def.id, def.opens!!.home)
            assertEquals(def.id, def.page.settingsPage, settingsParent(def.opens!!).let { if (it == SettingsPage.Comms) def.page.settingsPage else it })
        }
        val opened = SettingsRegistry.all.mapNotNull { it.opens }.toSet() +
            setOf(SettingsPage.HubOptions, SettingsPage.CommsApps, SettingsPage.QuickReplies, SettingsPage.Onboarding, SettingsPage.Root)
        val unreachable = SettingsPage.entries.filter { it.home == null && it !in opened }
        assertEquals(emptyList<SettingsPage>(), unreachable)
        SettingsHome.entries.forEach { assertEquals(SettingsPage.Root, settingsParent(it.settingsPage)) }
    }

    @Test fun everyValueRoundTripsAndIsSavedByTheWriter() {
        val persisted = setOf("look", "hub", "skinId", "counts", "aligned", "joystick", "hubDoubleTap", "haptics", "feel", "arcFeel",
            "clockVisible", "iconPack", "statsVisible", "hubSize", "searchPhoneSettings", "searchFiles", "searchEvents",
            "badges", "badgesInAllApps", "menuNotifications", "menuNotificationText", "markWorkApps", "wallpaperMode", "wallpaperLook")
        val values = SettingsRegistry.all.filter { it.read != null || it.write != null }
        assertTrue(values.size >= 40)
        for (def in values) {
            assertTrue("${def.id} needs both read and write", def.hasValue)
            for (base in bases) {
                val options = def.options(base)
                if (options.size < 2 && !def.shown(base)) continue // a row with one option is not shown (no icon pack installed)
                assertTrue("${def.id} has no options", options.size >= 2)
                val current = def.read!!(base)
                assertTrue("${def.id} reads $current of ${options.size}", current in options.indices)
                for (i in options.indices) {
                    val next = def.write!!(base, i)
                    assertEquals("${def.id} option $i read back", i, def.read!!(next))
                    val changed = changedFields(base, next)
                    if (i != current) assertTrue("${def.id} option $i changed nothing", changed.isNotEmpty())
                    assertTrue("${def.id} changes $changed, not all saved", persisted.containsAll(changed))
                    assertTrue("${def.id} changes $changed outside the backup", changed.all { it in backupKey })
                }
            }
        }
    }

    @Test fun everyStoredValueIsInTheBackup() {
        val specs = (BackupSpecs.all + AppBackup.HubSpec).associateBy { it.id }
        backupKey.forEach { (field, where) ->
            val spec = specs[where.first]
            assertNotNull("$field: store ${where.first}", spec)
            assertNotNull("$field: key ${where.second}", spec!!.key(where.second))
        }
    }

    @Test fun visualSettingsChangeWhatHomeDraws() {
        val failures = mutableListOf<String>()
        for (def in SettingsRegistry.all.filter { it.effect == SettingEffect.Visual }) for (base in bases) {
            if (!def.shown(base)) continue
            val before = HomeDraw.of(base)
            val current = def.read!!(base)
            for (i in def.options(base).indices) if (i != current) {
                if (HomeDraw.of(def.write!!(base, i)) == before) failures += "${def.id}=$i on ${base.look.wallpaper}/glass=${base.glass}/stats=${base.statsVisible}"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun aHiddenRowNeverChangesTheScreen() {
        val failures = mutableListOf<String>()
        for (def in SettingsRegistry.all.filter { it.hasValue }) for (base in bases) {
            if (def.shown(base)) continue
            val before = HomeDraw.of(base)
            for (i in def.options(base).indices) if (HomeDraw.of(def.write!!(base, i)) != before)
                failures += "${def.id}=$i hidden but visible on ${base.look.wallpaper}/glass=${base.glass}"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun keepTextReadableIsShownWithAChosenPicture() {
        val picture = SettingsSnapshot(skins = skins, look = LookSettings(wallpaper = WallpaperStyle.Image))
        assertTrue(SettingsRegistry.byId.getValue("WallpaperReadability").shown(picture))
        assertTrue(SettingsRegistry.byId.getValue("WallpaperDim").shown(picture))
        assertTrue(SettingsRegistry.shown(SettingsHome.Look, picture).any { it.id == "WallpaperReadability" })
    }

    @Test fun searchFindsEverySettingAndOpensItsPageAndRow() {
        SettingsRegistry.all.forEach { def ->
            assertTrue(def.id, SettingsRegistry.search(def.title).contains(def))
            val key = "setting:${def.id}"
            assertTrue(def.id, SettingsSearchEntries.all.any { it.key == key })
            assertEquals(SettingsTarget(def.page.settingsPage, def.id), SettingsSearchEntries.target(key))
        }
        assertTrue(SettingsRegistry.search("glass").any { it.id == "GlassEffect" })
        assertTrue(SettingsRegistry.search("dim").any { it.id == "WallpaperDim" })
        assertEquals(setOf("ArcHubOpacity", "StatsHubOpacity"), SettingsRegistry.search("opacity").map { it.id }.toSet())
        assertEquals("Arc hub opacity", SettingsRegistry.byId.getValue("ArcHubOpacity").fullTitle)
        assertNull(SettingsRegistry.byId["PanelOpacity"])
    }

    @Test fun hubsPageHasTheSameBlockForBothHubs() {
        val s = SettingsSnapshot(skins = skins, hub = HubAppearance(glass = true))
        val rows = SettingsRegistry.shown(SettingsHome.Hubs, s)
        val arc = rows.filter { it.group == "Arc hub" }.map { it.title to it.control }
        val stats = rows.filter { it.group == "Stats hub" }.map { it.title to it.control }
        assertEquals(listOf("Colour" to SettingControl.Colour, "Opacity" to SettingControl.Slider), arc)
        assertEquals(arc, stats)
        assertEquals(listOf("GlassEffect", "GlassRefraction"), rows.filter { it.group == "Glass, both hubs" }.map { it.id })
    }

    @Test fun defaultsDrawTheSameCardsAsBefore() {
        // Someone who changes nothing: the T171/T172 defaults (85 flat, 50 glass) and no explicit glass.
        for (skin in skins) {
            val s = SettingsSnapshot(skins = skins, skinId = skin.id)
            val glass = HubAppearance().glassEnabled(skin)
            val cards = HubCards.of(skin, s.look, HubAppearance())
            assertEquals(HubAppearance().arcSkin(skin, if (glass) 50 else 85, true), cards.arc)
            assertEquals(HubAppearance().statsSkin(skin, if (glass) 50 else 85, true), cards.stats)
        }
        assertEquals(true, HubAppearance().glassEnabled(glassSkin))
    }

    @Test fun glassIsOneRuleForBothHubsInEverySkin() {
        for (skin in skins) for (glass in listOf(true, false)) {
            val cards = HubCards.of(skin, LookSettings(wallpaper = WallpaperStyle.Image), HubAppearance(glass = glass))
            val expected = if (glass) ModuleStyle.frosted else ModuleStyle.solid
            assertEquals(skin.id, expected, cards.arc.shape.moduleStyle)
            assertEquals(skin.id, expected, cards.stats.shape.moduleStyle)
        }
    }

    @Test fun writerMergesOnlyTheChangedFields() {
        val old = LookSettings(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 0)
        val new = old.copy(wallpaperDimPercent = 80)
        val concurrent = old.copy(wallpaperImageRevision = 42)
        val merged = SettingsWriter.mergeLook(concurrent, old, new)
        assertEquals(80, merged.wallpaperDimPercent)
        assertEquals(42, merged.wallpaperImageRevision)
        val hub = SettingsWriter.mergeHub(HubAppearance(arcOpacity = 30), HubAppearance(), HubAppearance(glass = true))
        assertEquals(HubAppearance(glass = true, arcOpacity = 30), hub)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }

    /** A backup as 1.1.0 (T174) wrote it: the Look document with Panel opacity moved, and the hub keys of T173. */
    @Test fun aBackupFromBeforeTheReworkStillRestoresEverySetting() = runTest {
        val lookMemory = MemoryStore(); val hubMemory = MemoryStore()
        val full = FullBackup(FakeLayoutRepository(), listOf(
            BackupStore(AppBackup.LookSpec, PreferencesStoreAccess(lookMemory)),
            BackupStore(AppBackup.HubSpec, HubAppearanceBackupAccess(PreferencesStoreAccess(hubMemory)))))
        val oldLook = """{"iconShape":"Squircle","iconSizeDp":48,"clockStyle":"Bold","showDate":true,"showAlarm":false,""" +
            """"wallpaper":"Image","wallpaperDimPercent":60,"panelOpacityPercent":65,"wallpaperReadability":false,""" +
            """"imageReadabilityScrim":false,"includeWallpaperInBackups":true,"wallpaperImageRevision":5,"keepTextReadable":false}"""
        val text = """{"schema":"parc-backup","backupVersion":2,"layout":${LayoutJson.encode(ParcLayout())},"settings":{""" +
            """"look":{"look_v1":${kotlinx.serialization.json.JsonPrimitive(oldLook)}},""" +
            """"hub":{"arc_hub_colour":"Forest","stats_hub_colour":"Plum","hub_glass":"true","arc_hub_opacity":"default",""" +
            """"stats_hub_opacity":"40","hub_refraction":"false","header_time":true}}}"""
        full.apply(full.parse(text))
        val look = LookJson.decode(lookMemory.data.value[androidx.datastore.preferences.core.stringPreferencesKey("look_v1")]!!)
        val hub = HubAppearanceStore(hubMemory).appearance.first()
        val restored = HubOpacity.migratePanel(hub, look.panelOpacityPercent)
        val s = SettingsSnapshot(skins = skins, look = look, hub = restored)
        fun shown(id: String) = SettingsRegistry.byId.getValue(id).value(s)
        assertEquals("A picture I choose", shown("WallpaperImage"))
        assertEquals("60 %", shown("WallpaperDim"))
        assertEquals("Off", shown("WallpaperReadability"))
        assertEquals("Squircle", shown("IconShape"))
        assertEquals("48 dp", shown("IconSize"))
        assertEquals("On", shown("ShowDate"))
        assertEquals("On", shown("WallpaperImageBackup"))
        assertEquals("Forest", shown("ArcHubColour"))
        assertEquals("Plum", shown("StatsHubColour"))
        assertEquals("On", shown("GlassEffect"))
        assertEquals("Off", shown("GlassRefraction"))
        // The old Panel opacity (65) becomes the arc hub's own value; the stats hub keeps the 40 it had.
        assertEquals("65 %", shown("ArcHubOpacity"))
        assertEquals("40 %", shown("StatsHubOpacity"))
    }
}
