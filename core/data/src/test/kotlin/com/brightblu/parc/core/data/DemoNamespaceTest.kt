package com.brightblu.parc.core.data

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The demo namespace mapping (T120a). Path tests are pure; wrapper tests touch a temp folder. */
class DemoNamespaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root = File("/data/user/0/com.brightblu.parc")

    @Test
    fun `a normal directory maps under demo`() {
        assertEquals(File(root, "files/demo"), DemoNamespace.demoPath(File(root, "files")))
    }

    @Test
    fun `the mapping is idempotent`() {
        val once = DemoNamespace.demoPath(root)

        assertEquals(File(root, "demo"), once)
        assertEquals(once, DemoNamespace.demoPath(once))
    }

    @Test
    fun `wrap returns the same context when demo is off`() {
        val context: Context = FakeBaseContext(temp.root)

        assertSame(context, DemoNamespace.wrap(context, false))
    }

    @Test
    fun `the demo context redirects the five stores`() {
        val demo = DemoNamespace.wrap(FakeBaseContext(temp.root), true)
        val real = temp.root

        assertEquals(File(real, "demo"), demo.dataDir)
        assertEquals(File(real, "files/demo"), demo.filesDir)
        assertEquals(File(real, "no_backup/demo"), demo.noBackupFilesDir)
        assertEquals(File(real, "cache/demo"), demo.cacheDir)
        assertEquals(File(real, "databases/demo/parc.db"), demo.getDatabasePath("parc.db"))
    }

    @Test
    fun `the demo directories exist before a store writes`() {
        val demo = DemoNamespace.wrap(FakeBaseContext(temp.root), true)

        assertTrue(demo.dataDir.isDirectory)
        assertTrue(demo.filesDir.isDirectory)
        assertTrue(demo.noBackupFilesDir.isDirectory)
        assertTrue(demo.cacheDir.isDirectory)
        assertTrue(demo.getDatabasePath("parc.db").parentFile!!.isDirectory)
    }

    @Test
    fun `the demo context is its own application context`() {
        val demo = DemoNamespace.wrap(FakeBaseContext(temp.root), true)

        assertSame(demo, demo.applicationContext)
    }

    @Test
    fun `wrapping twice does not nest the namespace`() {
        val once = DemoNamespace.wrap(FakeBaseContext(temp.root), true)
        val twice = DemoNamespace.wrap(once, true)

        assertEquals(once.filesDir, twice.filesDir)
        assertEquals(once.dataDir, twice.dataDir)
        assertEquals(once.getDatabasePath("parc.db"), twice.getDatabasePath("parc.db"))
    }

    /** Stands in for the real application context; the wrapper never touches its base's files. */
    private class FakeBaseContext(private val root: File) : ContextWrapper(null) {
        override fun getDataDir(): File = root

        override fun getFilesDir(): File = File(root, "files")

        override fun getNoBackupFilesDir(): File = File(root, "no_backup")

        override fun getCacheDir(): File = File(root, "cache")

        override fun getDatabasePath(name: String): File = File(File(root, "databases"), name)
    }
}
