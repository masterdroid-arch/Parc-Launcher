package com.brightblu.parc

import com.brightblu.parc.core.data.timers.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class T183Test {
    private val zone = ZoneId.of("Europe/Lisbon")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    // ---- quick choices
    @Test fun inOneHourIsExactlyAnHour() {
        val now = at(2026, 11, 10, 10, 30)
        assertEquals(now + 3_600_000L, NoteReminderRules.inOneHour(now))
    }
    @Test fun thisEveningIsTodayBefore19() {
        assertEquals(at(2026, 11, 10, 19), NoteReminderRules.thisEvening(at(2026, 11, 10, 8, 15), zone))
        assertEquals(at(2026, 11, 10, 19), NoteReminderRules.thisEvening(at(2026, 11, 10, 18, 59), zone))
    }
    @Test fun thisEveningAfter19RollsToTomorrow() {
        assertEquals(at(2026, 11, 11, 19), NoteReminderRules.thisEvening(at(2026, 11, 10, 19, 0), zone))
        assertEquals(at(2026, 11, 11, 19), NoteReminderRules.thisEvening(at(2026, 11, 10, 22, 40), zone))
        assertEquals(at(2026, 1, 1, 19), NoteReminderRules.thisEvening(at(2025, 12, 31, 23, 59), zone))
    }
    @Test fun tomorrowMorningIsNextDayAt9EvenAfterMidnight() {
        assertEquals(at(2026, 11, 11, 9), NoteReminderRules.tomorrowMorning(at(2026, 11, 10, 8, 0), zone))
        assertEquals(at(2026, 11, 11, 9), NoteReminderRules.tomorrowMorning(at(2026, 11, 10, 23, 30), zone))
    }
    @Test fun pickerGivesFutureTimesOnly() {
        val now = at(2026, 11, 10, 10, 0)
        assertEquals(at(2026, 11, 10, 11, 30), NoteReminderRules.fromPicker(0, 11, 30, now, zone))
        assertNull(NoteReminderRules.fromPicker(0, 9, 0, now, zone))
        assertNull(NoteReminderRules.fromPicker(0, 10, 0, now, zone))
        assertEquals(at(2026, 11, 13, 7, 5), NoteReminderRules.fromPicker(3, 7, 5, now, zone))
    }
    @Test fun pickerStartIsInTheFutureOnAFiveMinuteStep() {
        val now = at(2026, 11, 10, 23, 58)
        val (day, hour, minute) = NoteReminderRules.pickerStart(now, zone)
        assertEquals(0, minute % 5)
        assertNotNull(NoteReminderRules.fromPicker(day, hour, minute, now, zone))
        assertEquals(1, day) // 23:58 plus a margin is already tomorrow
    }

    // ---- labels
    @Test fun labelsSayAroundWhenTheAlarmIsNotExact() {
        val now = at(2026, 11, 10, 8, 0)           // a Tuesday
        val when14 = at(2026, 11, 10, 14, 0)
        assertEquals("Reminder · Tue 14:00", NoteReminderRules.label(when14, now, zone, true, true, Locale.ENGLISH))
        assertEquals("Reminder · around Tue 14:00", NoteReminderRules.label(when14, now, zone, true, false, Locale.ENGLISH))
        assertEquals("Tue 2:00 PM", NoteReminderRules.whenText(when14, now, zone, false, Locale.ENGLISH))
        assertEquals("Wed 18 Nov 09:00", NoteReminderRules.whenText(at(2026, 11, 18, 9), now, zone, true, Locale.ENGLISH))
    }
    @Test fun firstLineHasNoMarksAndSkipsBlankLines() {
        assertEquals("Buy milk", NoteReminderRules.firstLine("\n\n# **Buy** milk\n- [ ] eggs"))
        assertEquals("", NoteReminderRules.firstLine(""))
        assertEquals("Note", NoteReminderRules.firstLine("   \n").ifEmpty { "Note" })
        val long = NoteReminderRules.firstLine("w".repeat(200))
        assertEquals(NoteReminderRules.MaxFirstLine, long.length); assertTrue(long.endsWith("…"))
    }
    @Test fun dueAllowsASmallEarlyArrivalButNotAFutureReminder() {
        assertTrue(NoteReminderRules.isDue(1_000L, 1_000L))
        assertTrue(NoteReminderRules.isDue(1_000L + 30_000L, 1_000L))
        assertFalse(NoteReminderRules.isDue(1_000L + 600_000L, 1_000L))
        assertFalse(NoteReminderRules.isDue(0L, 1_000L))
    }

    // ---- JSON
    @Test fun reminderRoundTripsAndOldNotesDecodeUnchanged() {
        val notes = listOf(Note("a", "x", remindAt = 123456L), Note("b", "y"))
        val raw = NotesJson.encode(notes)
        assertEquals(notes, NotesJson.decode(raw))
        assertFalse("a note without a reminder writes no field", raw.substringAfter("\"id\":\"b\"").contains("remindAt"))
        val old = NotesJson.decode("""[{"id":"o","text":"old","pinned":true,"updatedAt":5,"marks":2}]""").single()
        assertEquals(0L, old.remindAt); assertEquals("old", old.text); assertTrue(old.pinned)
        val older = NotesJson.decode("""[{"id":"o","text":"older","pinned":false,"updatedAt":5}]""").single()
        assertEquals(0L, older.remindAt)
        assertEquals(0L, NotesJson.decode("""[{"id":"o","text":"x","remindAt":-5}]""").single().remindAt)
    }

    // ---- store: alarms, cancel on delete
    private class FakeAlarms : NoteReminderAlarms {
        val armed = mutableMapOf<String, Long>()
        override fun schedule(noteId: String, at: Long) { armed[noteId] = at }
        override fun cancel(noteId: String) { armed.remove(noteId) }
    }
    private class MemoryNotes : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
            transform(state.value).also { state.value = it }
    }
    @Test fun settingAndClearingArmsAndCancelsTheAlarm() = runTest {
        val alarms = FakeAlarms()
        val store = NotesStore(MemoryNotes(), backgroundScope, alarms) { testScheduler.currentTime }
        store.save(Note("a", "call mum")); store.setReminder("a", 5_000L); runCurrent()
        assertEquals(mapOf("a" to 5_000L), alarms.armed)
        assertEquals(5_000L, store.notes.first().single().remindAt)
        store.setReminder("a", 0); runCurrent()
        assertTrue(alarms.armed.isEmpty()); assertEquals(0L, store.notes.first().single().remindAt)
        store.setReminder("missing", 9_000L); runCurrent()
        assertTrue("no alarm for a note that does not exist", alarms.armed.isEmpty())
    }
    @Test fun autosaveNeverOverwritesTheReminder() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope, FakeAlarms()) { testScheduler.currentTime }
        store.save(Note("a", "v1")); store.setReminder("a", 7_000L); runCurrent()
        store.save(Note("a", "v2 typed with a stale draft", remindAt = 0)); runCurrent()
        val note = store.notes.first().single()
        assertEquals("v2 typed with a stale draft", note.text); assertEquals(7_000L, note.remindAt)
    }
    @Test fun deletingANoteCancelsItsReminderAndUndoArmsItAgain() = runTest {
        val alarms = FakeAlarms()
        val store = NotesStore(MemoryNotes(), backgroundScope, alarms) { testScheduler.currentTime }
        store.save(Note("a", "x")); store.setReminder("a", 8_000L); runCurrent()
        assertEquals(8_000L, alarms.armed["a"])
        store.delete(Note("a", "x")); runCurrent()   // the editor's draft carries no reminder
        assertTrue(alarms.armed.isEmpty()); assertTrue(store.notes.first().isEmpty())
        store.undoDelete(); runCurrent()
        assertEquals(8_000L, alarms.armed["a"]); assertEquals(8_000L, store.notes.first().single().remindAt)
    }
    @Test fun firingClearsTheReminderOnceAndOnlyWhenDue() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope, FakeAlarms()) { testScheduler.currentTime }
        store.save(Note("a", "pay rent")); store.setReminder("a", 200_000L); runCurrent()
        assertNull("not due yet", store.fireReminder("a", 1_000L))
        assertEquals(200_000L, store.notes.first().single().remindAt)
        assertEquals("pay rent", store.fireReminder("a", 200_050L)?.text)
        assertEquals(0L, store.notes.first().single().remindAt)
        assertNull("a past reminder fires once", store.fireReminder("a", 200_100L))
        assertNull(store.fireReminder("gone", 200_100L))
    }
    @Test fun startUpArmsFutureRemindersAndReturnsMissedOnes() = runTest {
        val alarms = FakeAlarms()
        val data = MemoryNotes()
        val first = NotesStore(data, backgroundScope, alarms) { testScheduler.currentTime }
        first.save(Note("late", "l")); first.save(Note("soon", "s")); first.save(Note("none", "n"))
        first.setReminder("late", 1_000L); first.setReminder("soon", 50_000L); runCurrent()
        alarms.armed.clear()                       // a reboot loses the alarms
        val missed = first.rearmReminders(now = 2_000L)
        assertEquals(listOf("late"), missed.map { it.id })
        assertEquals(mapOf("soon" to 50_000L), alarms.armed)
    }
    @Test fun aNoteWithAReminderSurvivesBackupAndRestoreJson() {
        val raw = NotesJson.encode(listOf(Note("a", "x", remindAt = 99L)))
        assertEquals(99L, NotesJson.decode(NotesJson.migrate(raw)).single().remindAt)
    }

    // ---- timer from a note
    @Test fun timerFromANoteKeepsItsLabelAndNoteThroughTheCodec() {
        val book = TimerBook().start(600_000L, 1_000L, "Soup, ready; then | stir", "note-1")!!
        val timer = book.timers.single()
        assertEquals("Soup, ready; then | stir", timer.label); assertEquals("note-1", timer.noteId)
        val back = TimerCodec.decodeBook(TimerCodec.encodeBook(book)).timers.single()
        assertEquals(timer, back)
        // reset and resume keep the link
        assertEquals("note-1", book.reset(1).resume(1, 5_000L).timers.single().noteId)
    }
    @Test fun oldTimerLinesStillDecodeAndOrdinaryTimersWriteTheOldFormat() {
        val old = TimerCodec.decodeBook("1,60000,Running,99999,60000;2,300000,Idle,0,300000")
        assertEquals(2, old.timers.size); assertEquals("", old.timers[0].label); assertEquals("", old.timers[0].noteId)
        val ordinary = TimerBook().start(60_000L, 0L)!!
        assertEquals(5, TimerCodec.encodeBook(ordinary).split(',').size)
        assertEquals(0, TimerCodec.decodeBook("1,60000,Running,99999,60000,%ZZ,x").timers.single().label.length)
        assertEquals(0, TimerCodec.decodeBook("1,60000,Running,99999,60000,onlysix").timers.size)
    }
    @Test fun timerLabelIsCutAndShownInTheAlert() {
        val long = "z".repeat(200)
        assertEquals(TimerRules.MaxLabel, TimerBook().start(60_000L, 0L, long, "n")!!.timers.single().label.length)
        assertEquals("1 min timer finished", TimerAlertRules.text(60_000L))
        assertEquals("Soup · 1 min timer finished", TimerAlertRules.text(60_000L, "Soup"))
    }
    @Test fun openRequestIsConsumedOnlyByItsOwnNote() {
        NoteOpenRequests.post("n1")
        assertEquals("n1", NoteOpenRequests.pending.value)
        NoteOpenRequests.consume("other"); assertEquals("n1", NoteOpenRequests.pending.value)
        NoteOpenRequests.consume("n1"); assertNull(NoteOpenRequests.pending.value)
        NoteOpenRequests.post(" "); assertNull(NoteOpenRequests.pending.value)
    }
}
