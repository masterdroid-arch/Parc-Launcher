package com.brightblu.parc.core.data.focus

import com.brightblu.parc.core.data.FakePreferencesStore
import com.brightblu.parc.core.data.backup.BackupSpecs
import java.time.ZoneId
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class T112PersistenceTest {
    @Test fun t95AndT104DocumentsKeepEveryOriginalField() {
        // Released schema fixtures: all T95 fields and T104's multiple schedules/strict options.
        val fixtures = listOf(
            """[{"name":"Study","glyph":"◇","accent":4281554286,"appPolicy":"AllowList","hiddenPackages":["test.reader"],"folderIds":["tools"],"alwaysAllowed":["test.phone"],"hiddenComms":["test.social"],"silenceBadges":true,"syncDnd":true,"skinId":"midnight","hubPages":["power","clock"],"mainAndAllowedOnly":true,"iconTreatment":"Greyscale","priority":4,"pauseBeforeOpen":true,"strict":true,"confirmationPhrase":"END STUDY","enabled":false,"schedule":{"days":["Mon","Tue"],"startMinute":1320,"endMinute":420,"enabled":true},"extraSchedules":[{"days":["Sat"],"startMinute":600,"endMinute":660,"enabled":false}]}]""",
            """[{"name":"Work","hiddenPackages":["test.social"],"silenceBadges":true,"schedule":{"days":["Mon","Tue","Wed","Thu","Fri"],"startMinute":540,"endMinute":1020,"enabled":false},"glyph":"▣","appPolicy":"BlockList","strict":false,"confirmationPhrase":"old phrase","priority":2}]"""
        )
        fixtures.forEach { json ->
            val modes = FocusJson.decodeModes(json)
            assertEquals(1, modes.size)
            val mode = modes.single()
            assertEquals(listOf(30,120,300), mode.friction.delaySeconds)
            assertFalse(mode.friction.unlimitedPasses)
            val serialized = FocusJson.encodeModes(modes)
            val before = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray
            val after = kotlinx.serialization.json.Json.parseToJsonElement(serialized) as kotlinx.serialization.json.JsonArray
            val old = before.single() as kotlinx.serialization.json.JsonObject
            val new = after.single() as kotlinx.serialization.json.JsonObject
            old.forEach { (key, value) -> assertEquals("Preserve $key", value, new[key]) }
            assertEquals(modes, FocusJson.decodeModes(serialized))
            val edited = mode.copy(name = mode.name + " renamed", friction = mode.friction.copy(delaySeconds = listOf(5,30,120), unlimitedPasses = true))
            assertEquals(listOf(edited), FocusJson.decodeModes(FocusJson.encodeModes(listOf(edited))))
        }
        assertNotNull(BackupSpecs.focus.key(BackupSpecs.FocusModesKey))
    }
    @Test fun firmWaitsFiveSecondsAndRelaxedHasNoLimitOrCooldown() = runTest {
        val start = 1_800_000_000_000L
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { start + testScheduler.currentTime }, { ZoneId.of("UTC") })
        repo.save(FocusMode("Firm", pauseBeforeOpen = true, friction = FocusFriction(delaySeconds = listOf(5,5,5))))
        runCurrent(); repo.quickStart("Firm"); runCurrent()
        val end = repo.beginExit(FocusExit.End)!!
        assertEquals(5_000, repo.exitRemaining(end)); assertFalse(repo.completeExit(end))
        advanceTimeBy(5_000); runCurrent(); assertTrue(repo.completeExit(end)); runCurrent()
        repo.save(FocusMode("Relaxed", pauseBeforeOpen = true, friction = FocusFriction(delaySeconds = listOf(0,0,0), unlimitedPasses = true), ledger = FocusLedger(List(8) { FocusUse(start, true) })))
        runCurrent(); repo.quickStart("Relaxed"); runCurrent()
        val pass = repo.beginExit(FocusExit.Pass)!!
        assertEquals(0, repo.exitRemaining(pass)); assertTrue(repo.completeExit(pass))
        runCurrent(); advanceTimeBy(300_000); runCurrent()
        assertNotNull(repo.beginExit(FocusExit.Pass))
    }
    @Test fun reorderUsesListPrecedenceAndPreservesAllOtherSettings() = runTest {
        val repo = FocusRepository(FocusPrefs(FakePreferencesStore()), backgroundScope, { 1_800_000_000_000L }, { ZoneId.of("UTC") })
        val first = FocusMode("First", priority = 2, hiddenPackages = setOf("one"))
        val second = FocusMode("Second", priority = 7, friction = FocusFriction(challenge = FocusChallenge.Hold))
        repo.save(first); repo.save(second); runCurrent()
        repo.move("First", -1); runCurrent()
        assertEquals(listOf(first.copy(priority = 0), second.copy(priority = 0)), repo.modes.value)
        repo.move("First", -1); runCurrent(); assertEquals("First", repo.modes.value.first().name)
        val old = repo.modes.value.first()
        repo.delete(old.name); runCurrent(); repo.restoreDeleted(old, 0); runCurrent()
        assertEquals(listOf(first.copy(priority = 0), second.copy(priority = 0)), repo.modes.value)
        repo.restoreDeleted(old.copy(hiddenPackages = setOf("replace")), 0); runCurrent()
        assertEquals(setOf("one"), repo.modes.value.first().hiddenPackages)
    }
}
