package com.brightblu.parc.core.data.search

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brightblu.parc.core.data.FakePreferencesStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAliasesTest {

    @Test fun `an alias is one trimmed line and is cut at the limit`() {
        assertEquals("bank", SearchAliases.normalize("  bank \n"))
        assertEquals(SearchAliases.MaxLength, SearchAliases.normalize("x".repeat(SearchAliases.MaxLength + 20)).length)
        assertEquals("", SearchAliases.normalize("   "))
    }

    @Test fun `adding changing and clearing an alias changes only that app`() {
        var aliases = mapOf("a" to "one", "b" to "two")
        aliases = SearchAliases.updated(aliases, "a", "money")
        assertEquals(mapOf("a" to "money", "b" to "two"), aliases)
        aliases = SearchAliases.updated(aliases, "a", "  ")
        assertEquals(mapOf("b" to "two"), aliases)
        assertEquals(aliases, SearchAliases.updated(aliases, "", "ignored"))
    }

    @Test fun `the document round trips punctuation and survives a reload`() {
        val aliases = mapOf("p.Bank/Main@0" to "money & \"savings\"", "p.Maps/Main@0" to "карта")
        val encoded = SearchAliases.encode(aliases)
        assertEquals(aliases, SearchAliases.decode(encoded))
        assertFalse(encoded.contains("\n"))
    }

    @Test fun `missing, empty and malformed documents read as no aliases`() {
        assertTrue(SearchAliases.decode(null).isEmpty())
        assertTrue(SearchAliases.decode("").isEmpty())
        assertTrue(SearchAliases.decode("not json").isEmpty())
        assertTrue(SearchAliases.decode("""{"a": {"nested": 1}, "": "blank id", "b": "  "}""").isEmpty())
    }

    @Test fun `a stored alias is in the snapshot and goes through the backup spec`() = runTest {
        val source = FakePreferencesStore()
        SearchPrefs(source).setAlias("p.Bank/Main@0", "money")

        val snapshot = SearchPrefs(source).snapshot.first()
        assertEquals(mapOf("p.Bank/Main@0" to "money"), snapshot.aliases)
        assertEquals("money", SearchPrefs(source).aliases.first().getValue("p.Bank/Main@0"))

        val saved = com.brightblu.parc.core.data.backup.PreferencesStoreAccess(source)
            .read(com.brightblu.parc.core.data.backup.BackupSpecs.search)
        assertTrue(saved.containsKey("aliases"))

        val target = FakePreferencesStore()
        com.brightblu.parc.core.data.backup.PreferencesStoreAccess(target)
            .write(com.brightblu.parc.core.data.backup.BackupSpecs.search, saved)
        assertEquals("money", SearchPrefs(target).snapshot.first().aliases.getValue("p.Bank/Main@0"))
    }

    @Test fun `an alias written straight to the store is decoded by a fresh instance`() = runTest {
        val store = FakePreferencesStore(preferencesOf(
            stringPreferencesKey("aliases") to SearchAliases.encode(mapOf("p.Maps/Main@0" to "карта"))))
        assertEquals(mapOf("p.Maps/Main@0" to "карта"), SearchPrefs(store).aliases.first())
    }
}
