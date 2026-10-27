package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class T110Test {
    private class FakeStore(override var lastSeen: Int? = null) : SeenVersionStore
    @Test fun freshInstallIsSilent() {
        val store = FakeStore()
        assertFalse(claimUpdate(store, 10000)); assertEquals(10000, store.lastSeen)
        assertFalse(claimUpdate(store, 10000))
    }
    @Test fun normalAndSkippedUpdatesShowOnce() {
        for (old in listOf(1, 9900, 9999)) {
            val store = FakeStore(old)
            assertTrue(claimUpdate(store, 10000))
            assertFalse(claimUpdate(store, 10000))
            assertEquals(10000, store.lastSeen)
        }
    }
    @Test fun downgradeDoesNotLowerHighWaterMark() {
        val store = FakeStore(10100)
        assertFalse(claimUpdate(store, 10000)); assertEquals(10100, store.lastSeen)
        assertFalse(claimUpdate(store, 10100)); assertTrue(claimUpdate(store, 10200))
    }
    @Test fun changelogContractAndVersionFormula() {
        val entries = javaClass.classLoader!!.getResourceAsStream("changelog.json")!!.bufferedReader().use { parseChangelog(it.readText()) }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.zipWithNext().all { (a,b) -> a.versionCode < b.versionCode })
        assertEquals(1, entries.count { it.versionCode == BuildConfig.VERSION_CODE })
        val jargon = Regex("\\b(api|datastore|listener|minsdk|sdk|repository|totp)\\b", RegexOption.IGNORE_CASE)
        entries.forEach { entry ->
            val parts = entry.versionName.split('.').map(String::toInt)
            assertEquals(3, parts.size)
            assertTrue(parts[0] >= 0 && parts[1] in 0..99 && parts[2] in 0..99)
            assertEquals(parts[0]*10000+parts[1]*100+parts[2], entry.versionCode)
            LocalDate.parse(entry.date)
            assertTrue(entry.items.size in 1..6)
            entry.items.forEach {
                assertTrue(it.headline.isNotBlank() && it.headline.length <= 28)
                assertTrue(it.text.isNotBlank() && it.text.length <= 110)
                assertFalse(jargon.containsMatchIn(it.headline + " " + it.text))
            }
        }
        assertEquals(BuildConfig.VERSION_NAME, entries.single { it.versionCode == BuildConfig.VERSION_CODE }.versionName)
    }
}
