package com.brightblu.parc

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.brightblu.parc.core.data.backup.BackupCodec
import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.backup.PreferencesStoreAccess
import com.brightblu.parc.core.data.backup.StoreAccess
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T204TranslationBackupTest {
    private class Memory : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Values(var values: Map<String, Any> = emptyMap()) : StoreAccess {
        override suspend fun read(spec: BackupStoreSpec) = values
        override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) {
            this.values = this.values + values
        }
    }

    // Inspect the real app registration, then use memory adapters so no Android files are read.
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = FakeSharedPreferences()
    }
    private val specs get() = AppBackup.stores(context).map { it.spec }
    private val localEntry = TranslationEntry("Local source", "Local result", TranslateTarget.Portuguese, 5)
    private val oldEntry = TranslationEntry("Old source", "Old result", TranslateTarget.English, 1)

    @Test fun registriesExcludeHistoryAndDeviceEncryptedKeyBlob() {
        for (registered in listOf(BackupSpecs.all, specs, WeatherVariant.backupStores(context).map { it.spec })) {
            assertFalse(registered.any { it.id == TranslateStore.Name || it.id == "parc_translate_key" })
        }
        assertEquals(listOf(BackupSpecs.weather, DeepLKeyStore.BackupSpec), WeatherVariant.backupStores(context).map { it.spec })
    }

    @Test fun exportingWithLocalHistoryOmitsTheSectionAndItsText() = runTest {
        val memory = Memory()
        val history = TranslateStore(memory)
        history.add(localEntry)
        val settings = Values(mapOf("skin_id" to "moss"))
        val stores = specs.map { spec ->
            BackupStore(spec, when (spec.id) {
                TranslateStore.Name -> PreferencesStoreAccess(memory)
                BackupSpecs.Settings -> settings
                else -> Values()
            })
        }
        val text = FullBackup(FakeLayoutRepository(), stores).export()
        assertFalse(text.contains("\"translate\""))
        assertFalse(text.contains(localEntry.source))
        assertFalse(text.contains(localEntry.result))
        assertTrue(text.contains("moss"))
        assertEquals(listOf(localEntry), history.history.first())
    }

    @Test fun olderBackupsIgnoreHistoryWithoutErrorsOrChangingLocalHistory() = runTest {
        for (existing in listOf(emptyList(), listOf(localEntry))) {
            val memory = Memory()
            val history = TranslateStore(memory)
            existing.forEach { history.add(it) }
            val settings = Values(mapOf("skin_id" to "midnight"))
            val stores = specs.map { spec ->
                BackupStore(spec, when (spec.id) {
                    TranslateStore.Name -> PreferencesStoreAccess(memory)
                    BackupSpecs.Settings -> settings
                    else -> Values()
                })
            }
            val backup = FullBackup(FakeLayoutRepository(), stores)
            for (oldHistory in listOf(TranslationHistory.encode(listOf(oldEntry)), "[]", "garbage")) {
                val text = BackupCodec.encode(ParcLayout(), mapOf(
                    "translate" to mapOf("history" to oldHistory),
                    BackupSpecs.Settings to mapOf("skin_id" to "graphite"),
                ))
                val parsed = backup.parse(text)
                assertEquals(1, parsed.summary.settings)
                assertEquals(0, parsed.summary.skipped)
                assertEquals(parsed.summary, backup.apply(parsed))
                assertEquals("graphite", settings.values["skin_id"])
                assertEquals(existing, history.history.first())
            }
        }
    }

    @Test fun historyStillPersistsLocallyAndCanBeCleared() = runTest {
        val memory = Memory()
        TranslateStore(memory).add(localEntry)
        val reopened = TranslateStore(memory)
        assertEquals(listOf(localEntry), reopened.history.first())
        reopened.clear()
        assertTrue(reopened.history.first().isEmpty())
        assertNull(memory.data.first()[stringPreferencesKey("history")])
        assertTrue(TranslateStore(memory).history.first().isEmpty())
    }
}
