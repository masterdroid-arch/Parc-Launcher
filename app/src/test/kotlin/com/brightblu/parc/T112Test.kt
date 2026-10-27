package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.model.Metrics
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class T112Test {
    @Test fun summaryMatrixCoversAllPoliciesCountsSchedulesAndLevels() {
        var cases = 0
        for (policy in FocusAppPolicy.entries) for (count in listOf(0, 1, 5))
            for (schedule in 0..3) for (level in FocusLevel.entries) for (quiet in listOf(false, true)) {
                var mode = FocusMode("Work", appPolicy = policy, hiddenPackages = (1..count).map { "app$it" }.toSet(), silenceBadges = quiet,
                    schedule = FocusSchedule(if (schedule == 0) emptySet() else FocusDay.entries.take(5).toSet(), 540, if (schedule == 3) 420 else 1020),
                    extraSchedules = if (schedule == 2) listOf(FocusSchedule(setOf(FocusDay.Sat), 600, 660)) else emptyList())
                mode = applyFocusLevel(mode, level)
                val text = focusModeSentence(mode)
                assertTrue(text.startsWith("While Work is on, Parc"))
                assertTrue(text.contains(if (policy == FocusAppPolicy.AllowList) "allows only $count" else if (count == 0) "hides no apps yet" else "hides $count"))
                assertEquals(quiet, text.contains("quiets badges"))
                assertEquals(schedule == 0, text.contains("when you start it"))
                assertEquals(schedule == 3, text.contains("next day"))
                if (count == 1) assertFalse(text.contains("1 apps"))
                if (schedule == 2) { assertTrue(text.contains("1 other time")); assertFalse(text.contains("Sat, 10:00")) }
                assertFalse(text.contains("BlockList")); assertFalse(text.contains("AllowList"))
                cases++
            }
        assertEquals(192, cases)
    }
    @Test fun summaryDescribesLookAndLongNamesWithoutDroppingMeaning() {
        val name = "A very long personal focus title"
        FocusIconTreatment.entries.forEach { icons ->
            val text = focusModeSentence(FocusMode(name, skinId = "custom", iconTreatment = icons, hubPages = setOf("clock"), mainAndAllowedOnly = true, hiddenComms = setOf("one"), syncDnd = true))
            assertTrue(text.contains(name)); assertTrue(text.contains("Focus skin")); assertTrue(text.contains("1 Home page"))
            assertTrue(text.contains("messages from 1 app")); assertTrue(text.contains("Do Not Disturb"))
            if (icons != FocusIconTreatment.Normal) assertTrue(text.contains("${icons.name} icons"))
        }
        assertEquals("Nothing hidden yet", focusAppsValue(FocusMode("Quiet")))
        assertEquals("1 app", focusAppCount(1))
        assertEquals("0 apps", focusAppCount(0))
        assertEquals("Hides 1 app", focusAppsValue(FocusMode("Quiet", setOf("one"))))
    }
    @Test fun namedPaletteAndGlyphsAreCompleteUniqueAndSkinChoiceIsNull() {
        assertEquals(listOf("Teal", "Blue", "Violet", "Pink", "Red", "Orange", "Yellow", "Green"), FocusColours.map { it.first })
        assertEquals(8, FocusColours.map { it.second }.distinct().size)
        assertTrue(FocusColours.all { it.second ushr 24 == 255L })
        assertEquals(12, FocusGlyphs.size); assertEquals(12, FocusGlyphs.map { it.first }.distinct().size)
        assertEquals(12, FocusGlyphs.map { it.second }.distinct().size); assertNull(FocusMode("New").accent)
    }
    @Test fun levelsRoundTripAndCustomCombinationsAreNeverRelabelled() {
        val source = FocusMode("Study", hiddenPackages = setOf("one"), folderIds = setOf("games"), confirmationPhrase = "legacy", friction = FocusFriction(accountabilitySecret = "ABC"))
        for (level in listOf(FocusLevel.Relaxed, FocusLevel.Firm, FocusLevel.Strict)) {
            val mapped = applyFocusLevel(source, level)
            assertEquals(level, focusLevel(mapped)); assertEquals(source.hiddenPackages, mapped.hiddenPackages)
            assertEquals(source.folderIds, mapped.folderIds); assertEquals("ABC", mapped.friction.accountabilitySecret)
            assertEquals("legacy", mapped.confirmationPhrase)
            assertEquals(FocusLevel.Custom, focusLevel(mapped.copy(friction = mapped.friction.copy(passMinutes = 7))))
        }
        val firm = applyFocusLevel(source, FocusLevel.Firm)
        assertEquals(listOf(5,5,5), firm.friction.delaySeconds); assertEquals(2, firm.friction.passLimit)
        assertEquals(5, firm.friction.passMinutes); assertFalse(firm.friction.unlimitedPasses)
        assertTrue(applyFocusLevel(source, FocusLevel.Relaxed).friction.unlimitedPasses)
        assertTrue(applyFocusLevel(source, FocusLevel.Strict).strict)
        assertEquals(FocusLevel.Custom, focusLevel(source))
    }
    @Test fun debounceUsesTrailingEdgeAndUndoExpiresAtEightSeconds() {
        var now = 0L; var restored = 0
        val edits = FocusEditTiming { now }
        assertFalse(edits.ready()); edits.changed()
        now = Metrics.focusSaveDebounceMs - 1; assertFalse(edits.ready())
        edits.changed(); now += 1; assertFalse(edits.ready())
        now += Metrics.focusSaveDebounceMs; assertTrue(edits.ready()); edits.saved(); assertFalse(edits.ready())
        edits.changed(); val inFlight = edits.generation; edits.changed()
        now += Metrics.focusSaveDebounceMs; edits.saved(inFlight); assertTrue(edits.ready())
        edits.saved(); assertFalse(edits.ready())
        edits.offerUndo { restored++ }; now += 7_999; assertTrue(edits.undo()); assertEquals(1, restored); assertFalse(edits.undo())
        edits.offerUndo { restored++ }; now += 8_000; assertFalse(edits.undo()); assertEquals(1, restored)
        edits.offerUndo { restored += 10 }; edits.offerUndo { restored += 100 }; assertTrue(edits.undo()); assertEquals(101, restored)
    }
    @Test fun undoClearKeepsLaterEditsAndRestoresOnlyTheClearedSelection() {
        var now = 0L
        val before = FocusMode("Work", hiddenPackages = setOf("social"), folderIds = setOf("games"), alwaysAllowed = setOf("phone"))
        var current = before.copy(hiddenPackages = emptySet(), folderIds = emptySet())
        val edits = FocusEditTiming { now }
        edits.offerUndo { current = restoreFocusApps(current, before, true) }
        current = current.copy(name = "Renamed", silenceBadges = true, hiddenPackages = setOf("video"))
        now = 7_999; assertTrue(edits.undo())
        assertEquals("Renamed", current.name); assertTrue(current.silenceBadges)
        assertEquals(setOf("social", "video"), current.hiddenPackages); assertEquals(setOf("games"), current.folderIds)
        assertEquals(setOf("phone"), current.alwaysAllowed)
        current = current.copy(alwaysAllowed = emptySet())
        edits.offerUndo { current = restoreFocusApps(current, before, false) }
        assertTrue(edits.undo()); assertEquals(setOf("phone"), current.alwaysAllowed)
    }
    @Test fun dragChoosesTheDestinationAcrossSeveralCards() {
        val names = listOf("Work", "Study", "Sleep")
        val centers = mapOf("Work" to 200f, "Study" to 420f, "Sleep" to 710f)
        assertEquals(2, focusDropDelta(names, centers, "Work", 700f))
        assertEquals(-2, focusDropDelta(names, centers, "Sleep", 100f))
        assertEquals(0, focusDropDelta(names, centers, "Study", 410f))
        assertEquals(1, focusDropDelta(names, centers, "Study", 1000f))
        assertEquals(0, focusDropDelta(names, emptyMap(), "Work", 700f))
        assertEquals(0, focusDropDelta(names, centers, "Missing", 200f))
        val mode = FocusMode("Work", schedule = FocusSchedule(setOf(FocusDay.Mon), 540, 1020), extraSchedules = List(3) { FocusSchedule(setOf(FocusDay.Sat), 600, 660) })
        assertTrue(focusModeSentence(mode).contains("3 other times"))
        assertTrue(focusWhenValue(mode).contains("Sat, 10:00 to 11:00"))
    }
    @Test fun visibleEditorStringsHaveNoInternalVocabulary() {
        val dir = sequenceOf(File("src/main/kotlin/com/brightblu/parc"), File("app/src/main/kotlin/com/brightblu/parc")).first { it.exists() }
        val banned = Regex("(?i)\\b(token|accent|BlockList|AllowList|priority|range|DND|Configure|Save mode|friction)\\b")
        listOf("FocusSettingsPage.kt", "FocusFrictionSettings.kt", "FocusEditorModel.kt", "FocusHubPage.kt", "FocusRuntime.kt", "FocusExitDialog.kt").forEach { file ->
            // String contents only; enum names in expressions are implementation details.
            Regex("\"([^\"\\n]*)\"").findAll(File(dir, file).readText()).forEach { match ->
                val literal = match.groupValues[1].replace(Regex("\\$\\{[^}]*}"), "")
                assertFalse("$file: $literal", banned.containsMatchIn(literal))
            }
        }
    }
}
