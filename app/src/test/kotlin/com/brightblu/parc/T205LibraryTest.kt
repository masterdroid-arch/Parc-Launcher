package com.brightblu.parc

import android.content.Context
import android.content.ContextWrapper
import com.brightblu.parc.core.data.backup.BackupSpecs
import org.junit.Assert.*
import org.junit.Test

class T205LibraryTest {
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = FakeSharedPreferences()
    }

    @Test fun actualAppBackupRegistersLibraryExactlyOnceWithoutCovers() {
        val specs = AppBackup.stores(context).map { it.spec }
        assertEquals(listOf(BackupSpecs.library), specs.filter { it.id == "library" })
        assertEquals(listOf("books_v1", "marks_v1", "shelves_v1"),
            specs.single { it.id == "library" }.keys.map { it.name })
        assertFalse(specs.any { it.id == "book-covers" })
    }

    @Test fun playCannotOfferAllFilesAccessAndPersonalBuildsCan() {
        assertEquals(BuildConfig.FLAVOR != "play", BuildConfig.LIBRARY_ALL_FILES_ALLOWED)
        assertEquals("Allow file access so Find books can list PDF, EPUB, text and comic files on shared storage.",
            FindBooksAccess.Explanation)
    }
}
