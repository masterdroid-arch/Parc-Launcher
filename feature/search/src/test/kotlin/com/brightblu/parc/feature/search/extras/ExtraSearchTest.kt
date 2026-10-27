package com.brightblu.parc.feature.search.extras

import com.brightblu.parc.core.data.agenda.AgendaEvent
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AllAvailable = SettingsAvailability { true }

private class FakeFiles(
    var granted: Boolean = true,
    val records: List<FileRecord> = emptyList(),
    var throws: Boolean = false,
) : FileSource {
    var calls = 0
    override fun hasPermission(): Boolean = granted
    override fun search(query: String, limit: Int): List<FileRecord> {
        calls++
        if (throws) throw IllegalStateException("provider gone")
        return records
    }
}

private class FakeEvents(
    var granted: Boolean = true,
    val items: List<AgendaEvent> = emptyList(),
    var throws: Boolean = false,
) : EventSource {
    var calls = 0
    var lastWindow: Pair<Long, Long>? = null
    override fun hasPermission(): Boolean = granted
    override fun events(fromMs: Long, toMs: Long): List<AgendaEvent> {
        calls++
        lastWindow = fromMs to toMs
        if (throws) throw IllegalStateException("provider gone")
        return items
    }
}

private fun file(id: Long, name: String, added: Long = 0, kind: FileKind = FileKind.Image) =
    FileRecord(id, name, "content://media/$id", "image/jpeg", kind, added)

private const val Now = 1_800_000_000_000L
private const val Hour = 3_600_000L

private fun event(id: Long, title: String, begin: Long, allDay: Boolean = false, end: Long = begin + Hour) =
    AgendaEvent(id, 1, title, begin, end, allDay)

class PhoneSettingsMatchingTest {

    private fun titles(query: String, limit: Int = 5, availability: SettingsAvailability = AllAvailable) =
        ExtraRanking.phoneSettings(query, PhoneSettingsTable.all, availability, limit).map { it.title }

    @Test fun exactTitleComesFirst() {
        assertEquals("Wi-Fi", titles("wi-fi").first())
        assertEquals("Bluetooth", titles("bluetooth").first())
        assertEquals("Developer options", titles("Developer options").first())
    }

    @Test fun synonymsFindThePage() {
        assertEquals("Wi-Fi", titles("internet").first())
        assertEquals("Display", titles("brightness").first())
        assertEquals("Display", titles("dark mode").first())
        assertEquals("Sound", titles("volume").first())
        assertEquals("Security", titles("fingerprint").first())
        assertEquals("Date and time", titles("timezone").first())
        assertEquals("Language", titles("region").first())
        assertEquals("Developer options", titles("usb debugging").first())
    }

    @Test fun wifiWithoutTheHyphenFindsWiFi() {
        assertEquals("Wi-Fi", titles("wifi").first())
        assertEquals("Wi-Fi", titles("Wi Fi").first())
    }

    @Test fun typosStillFindThePage() {
        assertTrue("Bluetooth" in titles("bluetoth"))
        assertTrue("Bluetooth" in titles("blutooth"))
        assertTrue("Accessibility" in titles("accesibility"))
        assertTrue("Notifications" in titles("notificatons"))
        assertTrue("Battery saver" in titles("batery"))
        assertTrue("Location" in titles("locaton"))
        assertTrue("Storage" in titles("storge"))
    }

    @Test fun shortQueriesDoNotFuzzyMatchSoTheListStaysQuiet() {
        assertTrue(titles("x").isEmpty())
        assertTrue(titles("").isEmpty())
        assertTrue(titles("   ").isEmpty())
    }

    @Test fun unrelatedWordsMatchNothing() {
        assertTrue(titles("zebra giraffe").isEmpty())
        assertTrue(titles("qqqqqq").isEmpty())
    }

    @Test fun titleMatchBeatsASynonymMatchOfTheSameKind() {
        // "battery" is the title of Battery saver words and a synonym of Battery usage; both show, saver first.
        val result = titles("battery")
        assertEquals(listOf("Battery saver", "Battery usage"), result.take(2))
    }

    @Test fun anExactTitleBeatsALongerPrefixMatch() {
        val table = listOf(PhoneSetting("Sound effects", "a.b"), PhoneSetting("Sound", "a.c"))
        assertEquals(listOf("Sound", "Sound effects"),
            ExtraRanking.phoneSettings("sound", table, AllAvailable, 5).map { it.title })
    }

    @Test fun pagesMissingOnThisDeviceAreSkippedAndTheNextOneTakesTheirPlace() {
        val noWifi = SettingsAvailability { it != PhoneSettingsTable.all.first { s -> s.title == "Wi-Fi" }.action }
        assertFalse("Wi-Fi" in titles("wifi", availability = noWifi))
        val none = SettingsAvailability { false }
        assertTrue(titles("wifi", availability = none).isEmpty())
    }

    @Test fun theCapAppliesAfterTheAvailabilityCheck() {
        val hits = ExtraRanking.phoneSettings("battery", PhoneSettingsTable.all, AllAvailable, 1)
        assertEquals(1, hits.size)
        assertEquals(0, ExtraRanking.phoneSettings("battery", PhoneSettingsTable.all, AllAvailable, 0).size)
    }

    @Test fun sameActionIsListedOnce() {
        val table = listOf(PhoneSetting("Alpha page", "x.same"), PhoneSetting("Alpha other", "x.same"))
        assertEquals(1, ExtraRanking.phoneSettings("alpha", table, AllAvailable, 5).size)
    }

    @Test fun accentsAndCaseAreIgnored() {
        val table = listOf(PhoneSetting("Definições", "x.def"))
        assertEquals(1, ExtraRanking.phoneSettings("DEFINICOES", table, AllAvailable, 3).size)
    }

    @Test fun everyTableRowHasATitleAnActionAndNoDuplicateAction() {
        val all = PhoneSettingsTable.all
        assertTrue(all.size >= 25)
        all.forEach { assertTrue(it.title.isNotBlank() && it.action.isNotBlank()) }
        assertEquals(all.size, all.map { it.action }.toSet().size)
        listOf("Wi-Fi", "Bluetooth", "Display", "Battery saver", "Storage", "Apps", "Notifications", "Sound", "Location",
            "Privacy", "Accessibility", "Default apps", "Developer options", "Date and time", "Language", "Security")
            .forEach { name -> assertNotNull(name, all.firstOrNull { it.title == name }) }
    }

    @Test fun manifestQueriesListExactlyTheTableActions() {
        val manifest = listOf("src/main/AndroidManifest.xml", "feature/search/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }.readText()
        val declared = Regex("<action android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(PhoneSettingsTable.actions.toSet(), declared)
    }
}

class FileAndEventRankingTest {

    @Test fun filesMatchByNameWithExactThenPrefixThenSubstring() {
        val hits = ExtraRanking.files("trip", listOf(
            file(1, "my_trip_photo.jpg"), file(2, "trip.jpg"), file(3, "trip_notes.txt"), file(4, "unrelated.png")), 10)
        assertEquals(listOf(2L, 3L, 1L), hits.map { it.file.id })
    }

    @Test fun filesAreCappedAndNewestBreaksTies() {
        val files = (1L..10L).map { file(it, "photo_$it.jpg", added = it) }
        val hits = ExtraRanking.files("photo", files, 4)
        assertEquals(4, hits.size)
        assertEquals(listOf(10L, 9L, 8L, 7L), hits.map { it.file.id })
    }

    @Test fun fileNamesAreNotFuzzyMatched() {
        assertTrue(ExtraRanking.files("sunsat", listOf(file(1, "sunset.jpg")), 5).isEmpty())
    }

    @Test fun eventsMatchTitleSoonestFirstWithinTheWindow() {
        val events = listOf(
            event(1, "Dentist", Now + 50 * 24 * Hour),
            event(2, "Dentist check", Now + 2 * Hour),
            event(3, "Dentist", Now + 3 * Hour),
            event(4, "Lunch", Now + Hour),
            event(5, "Dentist", Now + 70 * 24 * Hour),
        )
        val hits = ExtraRanking.events("dentist", events, Now, Now + 60 * 24 * Hour, 10)
        assertEquals(listOf(3L, 1L, 2L), hits.map { it.event.eventId })
    }

    @Test fun eventsThatAlreadyEndedAreDroppedAndRunningOnesStay() {
        val events = listOf(
            event(1, "Standup", Now - 3 * Hour, end = Now - 2 * Hour),
            event(2, "Standup", Now - Hour / 2, end = Now + Hour),
        )
        assertEquals(listOf(2L), ExtraRanking.events("standup", events, Now, Now + 24 * Hour, 5).map { it.event.eventId })
    }

    @Test fun eventsAreCappedDeduplicatedAndBlankTitlesIgnored() {
        val events = listOf(
            event(1, "Gym", Now + Hour), event(1, "Gym", Now + Hour),
            event(2, "", Now + Hour), event(3, "Gym", Now + 2 * Hour), event(4, "Gym", Now + 3 * Hour),
        )
        val hits = ExtraRanking.events("gym", events, Now, Now + 24 * Hour, 2)
        assertEquals(listOf(1L, 3L), hits.map { it.event.eventId })
    }

    @Test fun eventTextNamesTheDayAndTime() {
        val zone = ZoneId.of("UTC")
        assertEquals("Today 10:00", ExtraText.eventWhen(event(1, "x", Now + 2 * Hour), Now, zone, true))
        assertEquals("Tomorrow 10:00", ExtraText.eventWhen(event(1, "x", Now + 26 * Hour), Now, zone, true))
        assertEquals("Tomorrow, all day", ExtraText.eventWhen(event(1, "x", Now + 24 * Hour - 8 * Hour, allDay = true), Now, zone, true))
        assertTrue(ExtraText.eventWhen(event(1, "x", Now + 5 * 24 * Hour), Now, zone, true).matches(Regex("\\w{3} \\d+ \\w{3} \\d\\d:\\d\\d")))
        assertTrue(ExtraText.eventWhen(event(1, "x", Now - Hour), Now, zone, true).endsWith("now"))
    }
}

class ExtraSearchTest {

    private fun search(
        files: FakeFiles = FakeFiles(),
        events: FakeEvents = FakeEvents(),
        availability: SettingsAvailability = AllAvailable,
    ) = ExtraSearch(availability, files, events, clock = { Now })

    @Test fun everythingOnReturnsAllThreeGroups() {
        val extra = search(
            FakeFiles(records = listOf(file(1, "wifi_password.txt"))),
            FakeEvents(items = listOf(event(1, "Wifi installer visit", Now + Hour))),
        )
        val results = extra.search("wifi", ExtraToggles())
        assertEquals(listOf("Wi-Fi"), results.phoneSettings.map { it.title })
        assertEquals(1, results.files.size)
        assertEquals(1, results.events.size)
        assertFalse(results.isEmpty)
    }

    @Test fun eachToggleSilencesOnlyItsOwnSource() {
        val files = FakeFiles(records = listOf(file(1, "wifi.txt")))
        val events = FakeEvents(items = listOf(event(1, "wifi visit", Now + Hour)))
        val extra = search(files, events)
        val noSettings = extra.search("wifi", ExtraToggles(phoneSettings = false))
        assertTrue(noSettings.phoneSettings.isEmpty()); assertEquals(1, noSettings.files.size); assertEquals(1, noSettings.events.size)
        val noFiles = extra.search("wifi", ExtraToggles(files = false))
        assertTrue(noFiles.files.isEmpty()); assertEquals(1, noFiles.phoneSettings.size); assertEquals(1, noFiles.events.size)
        val noEvents = extra.search("wifi", ExtraToggles(events = false))
        assertTrue(noEvents.events.isEmpty()); assertEquals(1, noEvents.files.size)
        assertTrue(extra.search("wifi", ExtraToggles(false, false, false)).isEmpty)
    }

    @Test fun aSwitchedOffSourceIsNeverQueried() {
        val files = FakeFiles(records = listOf(file(1, "wifi.txt")))
        val events = FakeEvents(items = listOf(event(1, "wifi visit", Now + Hour)))
        search(files, events).search("wifi", ExtraToggles(files = false, events = false))
        assertEquals(0, files.calls)
        assertEquals(0, events.calls)
    }

    @Test fun withoutPermissionFilesAndEventsStaySilentAndUntouched() {
        val files = FakeFiles(granted = false, records = listOf(file(1, "wifi.txt")))
        val events = FakeEvents(granted = false, items = listOf(event(1, "wifi visit", Now + Hour)))
        val results = search(files, events).search("wifi", ExtraToggles())
        assertTrue(results.files.isEmpty())
        assertTrue(results.events.isEmpty())
        assertEquals(1, results.phoneSettings.size)
        assertEquals(0, files.calls)
        assertEquals(0, events.calls)
    }

    @Test fun aRevokedPermissionBetweenCheckAndQueryDoesNotCrash() {
        val results = search(FakeFiles(throws = true), FakeEvents(throws = true)).search("wifi", ExtraToggles())
        assertTrue(results.files.isEmpty())
        assertTrue(results.events.isEmpty())
        assertEquals(1, results.phoneSettings.size)
    }

    @Test fun aFailingAvailabilityCheckOnlyLosesSettings() {
        val boom = SettingsAvailability { throw IllegalStateException("pm died") }
        val results = search(FakeFiles(records = listOf(file(1, "wifi.txt"))), availability = boom).search("wifi", ExtraToggles())
        assertTrue(results.phoneSettings.isEmpty())
        assertEquals(1, results.files.size)
    }

    @Test fun eachSourceHonoursItsOwnCap() {
        val files = FakeFiles(records = (1L..20L).map { file(it, "report_$it.pdf") })
        val events = FakeEvents(items = (1L..20L).map { event(it, "report meeting $it", Now + it * Hour) })
        val results = search(files, events).search("report", ExtraToggles(), ExtraCaps(phoneSettings = 1, files = 2, events = 3))
        assertEquals(2, results.files.size)
        assertEquals(3, results.events.size)
        val settings = search().search("battery", ExtraToggles(), ExtraCaps(phoneSettings = 1))
        assertEquals(1, settings.phoneSettings.size)
    }

    @Test fun defaultCapsAreSmall() {
        val caps = ExtraCaps()
        assertTrue(caps.phoneSettings <= 5 && caps.files <= 5 && caps.events <= 5)
    }

    @Test fun theCalendarWindowIsTheNextSixtyDays() {
        val events = FakeEvents()
        search(events = events).search("anything", ExtraToggles())
        assertEquals(Now to Now + 60L * 24 * Hour, events.lastWindow)
    }

    @Test fun blankAndOneLetterQueriesDoNothing() {
        val files = FakeFiles(records = listOf(file(1, "a.txt")))
        val extra = search(files)
        assertTrue(extra.search("", ExtraToggles()).isEmpty)
        assertTrue(extra.search("  ", ExtraToggles()).isEmpty)
        assertTrue(extra.search("a", ExtraToggles()).isEmpty)
        assertEquals(0, files.calls)
    }

    @Test fun permissionProbesNeverThrow() {
        val extra = ExtraSearch(AllAvailable, object : FileSource {
            override fun hasPermission(): Boolean = throw SecurityException("x")
            override fun search(query: String, limit: Int): List<FileRecord> = emptyList()
        }, object : EventSource {
            override fun hasPermission(): Boolean = throw SecurityException("x")
            override fun events(fromMs: Long, toMs: Long): List<AgendaEvent> = emptyList()
        })
        assertFalse(extra.filesGranted())
        assertFalse(extra.calendarGranted())
        assertNull(extra.search("wifi", ExtraToggles()).files.firstOrNull())
    }
}
