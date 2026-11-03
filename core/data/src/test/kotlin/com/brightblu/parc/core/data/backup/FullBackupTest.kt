package com.brightblu.parc.core.data.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T87: the whole-backup contract over fake DataStores and a fake layout repository. */
class FullBackupTest {

    private class Rig(layout: ParcLayout = ParcLayout()) {
        val repo = FakeLayoutRepository(layout)
        val prefs = BackupSpecs.all.associateWith { FakePreferencesStore() }
        val stores = prefs.map { (spec, store) -> BackupStores.of(spec, store) }
        val backup = FullBackup(repo, stores)
        suspend fun values(): Map<String, Map<String, Any>> =
            stores.associate { it.spec.id to it.access.read(it.spec) }
    }

    private fun sample(key: BackupKey): Any = when (key.type) {
        BackupValueType.Bool -> true
        BackupValueType.Int -> 42
        BackupValueType.Long -> 123_456_789_012L
        BackupValueType.Text -> "v-${key.name}"
        BackupValueType.TextSet -> setOf("one.${key.name}", "two.${key.name}")
    }

    private suspend fun fill(rig: Rig) {
        for (store in rig.stores) store.access.write(store.spec, store.spec.keys.associate { it.name to sample(it) })
    }

    private fun layout() = ParcLayout(
        arcs = listOf(
            ArcAssignment(
                ArcSpec("A1", 0, 100f, 0f, 90f, 4, Layer.Home),
                mapOf(0 to Slot.App(AppId("a/one")), 1 to Slot.Folder(FolderId("f1"))),
            ),
        ),
        folders = listOf(
            Folder(FolderId("f1"), "Tools", appIds = listOf(AppId("a/two"), AppId("a/three"))),
            Folder(FolderId("f2"), "Media", appIds = listOf(AppId("a/three"))),
        ),
        pins = setOf(AppId("a/one")),
        hidden = setOf(AppId("a/hidden")),
    )

    @Test
    fun `round trip restores every key of every store and the layout`() = runTest {
        val source = Rig(layout())
        fill(source)
        val before = source.values()
        assertTrue(before.values.all { it.isNotEmpty() })
        val text = source.backup.export()

        val target = Rig()
        val parsed = target.backup.parse(text)
        val summary = target.backup.apply(parsed)

        assertEquals(before, target.values())
        assertEquals(source.repo.layout.value, target.repo.layout.value)
        assertEquals(BackupSpecs.all.sumOf { it.keys.size }, summary.settings)
        assertEquals(2, summary.folders)
        assertEquals(3, summary.apps)
        assertEquals("2 folders, 3 apps, ${summary.settings} settings", summary.describe())
    }

    @Test
    fun `legacy hub backup restores Standard over Tall without dropping expanded or page config`() = runTest {
        val source = Rig()
        val config = "system:true:battery:"
        source.prefs.getValue(BackupSpecs.hub).edit { it[stringPreferencesKey("config_v1")] = config }
        source.prefs.getValue(BackupSpecs.settings).edit { it[booleanPreferencesKey("stats_expanded")] = true }
        val target = Rig()
        target.prefs.getValue(BackupSpecs.hub).edit { it[stringPreferencesKey("hub_size")] = "tall"; it[stringPreferencesKey("clock_options")] = "false|true|Asia/Tokyo" }
        target.backup.apply(target.backup.parse(source.backup.export()))
        assertEquals("standard", target.values().getValue(BackupSpecs.Hub)["hub_size"])
        assertEquals("true|false|", target.values().getValue(BackupSpecs.Hub)["clock_options"])
        assertEquals(config, target.values().getValue(BackupSpecs.Hub)["config_v1"])
        assertEquals(true, target.values().getValue(BackupSpecs.Settings)["stats_expanded"])
    }

    @Test
    fun `every saved hub size survives a full backup restore`() = runTest {
        for (size in listOf("compact", "standard", "tall")) {
            val source = Rig()
            source.prefs.getValue(BackupSpecs.hub).edit { it[stringPreferencesKey("hub_size")] = size }
            val target = Rig()
            target.backup.apply(target.backup.parse(source.backup.export()))
            assertEquals(size, target.values().getValue(BackupSpecs.Hub)["hub_size"])
        }
    }

    @Test
    fun `every spec has at least one key and ids are unique`() {
        assertEquals(BackupSpecs.all.size, BackupSpecs.all.map { it.id }.toSet().size)
        assertTrue(BackupSpecs.all.all { it.keys.isNotEmpty() })
        for (spec in BackupSpecs.all) assertEquals(spec.keys.size, spec.keys.map { it.name }.toSet().size)
    }

    @Test
    fun `history and learned keys are never exported`() = runTest {
        val rig = Rig()
        val settings = rig.prefs.getValue(BackupSpecs.settings)
        settings.edit {
            it[stringPreferencesKey("usageSlots")] = "A2:0,A2:1"
            it[stringPreferencesKey("skin_id")] = "midnight"
            it[booleanPreferencesKey("onboarding_completed")] = true
        }
        rig.prefs.getValue(BackupSpecs.search).edit { it[stringPreferencesKey("recent_searches")] = "private query" }
        val text = rig.backup.export()
        assertFalse(text.contains("usageSlots"))
        assertFalse(text.contains("recent_searches"))
        assertFalse(text.contains("private query"))
        assertFalse(text.contains("onboarding_completed"))
        assertTrue(text.contains("skin_id"))
    }

    @Test
    fun `an unlisted key in the file is never written`() = runTest {
        val rig = Rig()
        val text = """{"schema":"parc-backup","backupVersion":2,"settings":{"settings":
            {"usageSlots":"A2:0","skin_id":"paper","evil":"x"}}}"""
        val parsed = rig.backup.parse(text)
        rig.backup.apply(parsed)
        val snapshot = rig.prefs.getValue(BackupSpecs.settings).data.first().asMap().keys.map { it.name }
        assertEquals(listOf("skin_id"), snapshot)
    }

    @Test
    fun `arc feel keys travel in the backup and restore`() = runTest {
        val source = Rig(layout())
        val feel = source.stores.single { it.spec.id == BackupSpecs.Feel }
        feel.access.write(feel.spec, mapOf("arc_spin_limit" to "free", "arc_bounce" to "playful", "arc_magnify" to "dock", "arc_home_arcs" to "two"))
        val text = source.backup.export()
        val target = Rig(layout())
        target.backup.apply(target.backup.parse(text))
        val restored = target.values().getValue("feel")
        assertEquals("free", restored["arc_spin_limit"])
        assertEquals("playful", restored["arc_bounce"])
        assertEquals("dock", restored["arc_magnify"])
        assertEquals("two", restored["arc_home_arcs"])
    }

    @Test
    fun `a backup made before the arc keys restores without them so defaults apply`() = runTest {
        val rig = Rig(layout())
        val text = """{"schema":"parc-backup","backupVersion":2,"settings":{"feel":{"haptic_preset":"sharp","sound_theme":"synth","sound_volume":30}}}"""
        rig.backup.apply(rig.backup.parse(text))
        val feel = rig.values().getValue("feel")
        assertEquals("sharp", feel["haptic_preset"])
        assertEquals(setOf("haptic_preset", "sound_theme", "sound_volume"), feel.keys)
    }

    @Test
    fun `a partial file restores only what it contains`() = runTest {
        val rig = Rig(layout())
        fill(rig)
        val layoutBefore = rig.repo.layout.value
        val text = """{"schema":"parc-backup","backupVersion":2,"settings":{"feel":{"sound_volume":10}}}"""
        val summary = rig.backup.apply(rig.backup.parse(text))
        val values = rig.values()
        assertEquals(10, values.getValue("feel")["sound_volume"])
        assertEquals("v-haptic_preset", values.getValue("feel")["haptic_preset"])
        assertEquals("v-skin_id", values.getValue("settings")["skin_id"])
        assertEquals(layoutBefore, rig.repo.layout.value)
        assertEquals(1, summary.settings)
        assertEquals("1 setting", summary.describe())
    }

    @Test
    fun `a version 1 fixture is read as a layout only backup`() = runTest {
        val rig = Rig()
        val parsed = rig.backup.parse(V1Fixture)
        assertEquals(1, parsed.version)
        assertEquals(1, parsed.summary.folders)
        assertEquals(2, parsed.summary.apps)
        assertEquals(0, parsed.summary.settings)
        rig.backup.apply(parsed)
        assertEquals("Tools", rig.repo.layout.value.folders.single().name)
        assertEquals(mapOf("hub_size" to "standard", "clock_options" to "true|false|"), rig.values().getValue(BackupSpecs.Hub))
        assertTrue(rig.values().filterKeys { it != BackupSpecs.Hub }.values.all { it.isEmpty() })
    }

    @Test
    fun `a version 2 fixture restores layout and settings and ignores unknown keys`() = runTest {
        val rig = Rig()
        val parsed = rig.backup.parse(V2Fixture)
        assertEquals(2, parsed.version)
        val summary = rig.backup.apply(parsed)
        assertEquals(1, rig.repo.layout.value.folders.size)
        val values = rig.values()
        assertEquals("paper", values.getValue("settings")["skin_id"])
        assertEquals(setOf("pkg.a", "pkg.b"), values.getValue("settings")["comms_apps"])
        assertEquals(false, values.getValue("ui_prefs")["joystick_aim"])
        assertEquals(3, summary.settings)
    }

    @Test
    fun `wrong typed and oversized values are skipped not written`() = runTest {
        val rig = Rig()
        val big = "x".repeat(70_000)
        val text = """{"schema":"parc-backup","backupVersion":2,"settings":{
            "feel":{"sound_volume":"loud","haptic_preset":5,"sound_theme":"ok"},
            "ui_prefs":{"joystick_aim":"yes","hub_double_tap":false},
            "look":{"look_v1":"$big"},
            "settings":{"comms_apps":["a",1],"skin_id":null},
            "hub":"nope"}}"""
        val parsed = rig.backup.parse(text)
        rig.backup.apply(parsed)
        val values = rig.values()
        assertEquals(mapOf<String, Any>("sound_theme" to "ok"), values.getValue("feel"))
        assertEquals(mapOf<String, Any>("hub_double_tap" to false), values.getValue("ui_prefs"))
        assertTrue(values.getValue("look").isEmpty())
        assertTrue(values.getValue("settings").isEmpty())
        assertEquals(2, parsed.summary.settings)
        assertTrue(parsed.summary.skipped >= 7)
    }

    @Test
    fun `hostile documents are refused cleanly`() = runTest {
        val rig = Rig(layout())
        val before = rig.repo.layout.value
        val hostile = listOf(
            "",
            "   ",
            "not json",
            "[1,2,3]",
            "null",
            "{}",
            """{"arcs":"nope"}""",
            """{"schema":"other","backupVersion":2}""",
            """{"schema":"parc-backup","backupVersion":99,"layout":{"arcs":[]}}""",
            """{"schema":"parc-backup","backupVersion":0}""",
            """{"schema":"parc-backup","backupVersion":2}""",
            """{"schema":"parc-backup","backupVersion":2,"layout":{"arcs":[]},"settings":""",
            V1Fixture.take(V1Fixture.length / 2),
            "[".repeat(200_000),
            "x".repeat(BackupCodec.MaxDocumentChars + 1),
            """{"schema":"parc-backup","backupVersion":2,"layout":{"version":7,"arcs":[]}}""",
        )
        for (doc in hostile) {
            try {
                rig.backup.parse(doc)
                fail("should be refused: ${doc.take(40)}")
            } catch (e: BackupFormatException) {
                // expected
            }
        }
        assertEquals(before, rig.repo.layout.value)
        assertTrue(rig.values().values.all { it.isEmpty() })
    }

    @Test
    fun `a broken layout section is skipped when settings are usable`() = runTest {
        val rig = Rig(layout())
        val text = """{"schema":"parc-backup","backupVersion":2,"layout":{"arcs":"bad"},
            "settings":{"feel":{"sound_volume":5}}}"""
        val parsed = rig.backup.parse(text)
        assertFalse(parsed.summary.hasLayout)
        assertEquals(1, parsed.summary.skipped)
        rig.backup.apply(parsed)
        assertEquals(layout(), rig.repo.layout.value)
        assertEquals("1 setting", parsed.summary.describe())
    }

    @Test
    fun `export is deterministic and never carries comms or diagnostics words`() = runTest {
        val rig = Rig(layout())
        fill(rig)
        val a = rig.backup.export()
        val b = rig.backup.export()
        assertEquals(a, b)
        assertNotNull(BackupSpecs.byId("feel"))
        assertNull(BackupSpecs.byId("diagnostics"))
        assertNull(BackupSpecs.byId("comms_history"))
    }

    private companion object {
        const val V1Fixture = """{"version":1,"arcs":[{"arc":{"id":"A1","index":0,"radiusDp":100.0,
"startAngle":0.0,"endAngle":90.0,"slotCapacity":4,"layer":"Home"},"slots":{"0":{"type":"com.brightblu.parc.model.Slot.App","id":"a/one"}}}],
"folders":[{"id":"f1","name":"Tools","tags":[],"appIds":["a/one","a/two"]}],"pins":["a/one"],"hidden":[],"future":1}"""

        const val V2Fixture = """{"schema":"parc-backup","backupVersion":2,"note":"ignored",
"layout":{"version":1,"arcs":[],"folders":[{"id":"f1","name":"Tools","appIds":["a/one"]}],"pins":[],"hidden":[],"extra":true},
"settings":{"settings":{"skin_id":"paper","comms_apps":["pkg.b","pkg.a"],"mystery":1},
"ui_prefs":{"joystick_aim":false},"nonexistent_store":{"a":1}}}"""
    }
}
