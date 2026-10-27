package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** T87: the app-local half of the backup stays in step with the stores it names. */
class AppBackupTest {
    @Test fun focusModeDocumentIncludesV2InAppBackup() {
        assertEquals(BackupSpecs.focus, AppBackup.FocusModesSpec)
        assertNotNull(AppBackup.FocusModesSpec.key(BackupSpecs.FocusModesKey))
        val mode = com.brightblu.parc.core.data.focus.FocusPreset.Sleep.mode().copy(strict = true, priority = 3,
            friction = com.brightblu.parc.core.data.focus.FocusFriction(15, 5, com.brightblu.parc.core.data.focus.PassPeriod.Week,
                com.brightblu.parc.core.data.focus.FocusChallenge.Accountability, "JBSWY3DPEHPK3PXP", delaySeconds = listOf(5, 30, 120), unlimitedPasses = true),
            ledger = com.brightblu.parc.core.data.focus.FocusLedger(listOf(com.brightblu.parc.core.data.focus.FocusUse(1234, true))))
        val encoded = com.brightblu.parc.core.data.focus.FocusJson.encodeModes(listOf(mode))
        assertEquals(listOf(mode), com.brightblu.parc.core.data.focus.FocusJson.decodeModes(encoded))
    }


    @Test
    fun `ui prefs round trip through the shared preferences adapter and skip transient keys`() = runTest {
        val source = FakeSharedPreferences()
        for (key in BackupSpecs.uiPrefs.keys) {
            source.data[key.name] = when (key.type) {
                com.brightblu.parc.core.data.backup.BackupValueType.Text -> "1m"
                com.brightblu.parc.core.data.backup.BackupValueType.Int -> 3
                else -> true
            }
        }
        source.data["mode"] = "AllApps"
        source.data["rotations"] = "A1=3.0"
        source.data["hint_seen"] = true
        val access = SharedPreferencesStoreAccess(source)

        val read = access.read(BackupSpecs.uiPrefs)

        assertEquals(BackupSpecs.uiPrefs.keys.map { it.name }.toSet(), read.keys)
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, read.filterKeys { it != "joystick_aim" } + mapOf("rotations" to "x", "joystick_aim" to "bad"))
        assertEquals(read - "joystick_aim", SharedPreferencesStoreAccess(target).read(BackupSpecs.uiPrefs))
        assertFalse(target.data.containsKey("rotations"))
        assertFalse(target.data.containsKey("joystick_aim"))
    }

    @Test
    fun `feel and gesture key names match the stores`() {
        val feel = BackupSpecs.feel.keys.map { it.name }.toSet()
        assertEquals(
            setOf(FeelPrefsCodec.HapticKey, FeelPrefsCodec.SoundKey, FeelPrefsCodec.VolumeKey),
            feel,
        )
        assertEquals(
            GestureSlot.entries.map { it.key }.toSet(),
            BackupSpecs.gestures.keys.map { it.name }.toSet(),
        )
        assertNotNull(BackupSpecs.byId("ui_prefs"))
    }
}
