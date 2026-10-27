package com.brightblu.parc.core.data

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * The demo store namespace (T120a). This file is the only place the namespace is computed: `:app`
 * builds every store from the context returned by [wrap], so Room, DataStore and file-backed
 * stores resolve under `demo/` while demo mode is on.
 */
object DemoNamespace {
    /** Last path component that marks the demo namespace. */
    const val DirectoryName = "demo"

    /**
     * Maps a directory to its demo twin: `base/demo`. Pure and idempotent: a directory already
     * named [DirectoryName] maps to itself.
     */
    fun demoPath(base: File): File =
        if (base.name == DirectoryName) base else File(base, DirectoryName)

    /**
     * Returns [context] unchanged when [demo] is false. When it is true, returns a wrapper whose
     * data, files, no-backup and cache directories and database path sit under `demo/`.
     *
     * Call it once at process start, before any store is built: Room and the DataStore delegates
     * cache their first instance, and DataStore reads `applicationContext` itself.
     */
    fun wrap(context: Context, demo: Boolean): Context =
        if (demo) DemoContext(context) else context
}

private class DemoContext(private val base: Context) : ContextWrapper(base) {

    /** DataStore resolves its file from `applicationContext`, so it must be the wrapper. */
    override fun getApplicationContext(): Context = this

    override fun getDataDir(): File = demoDir(base.dataDir)

    override fun getFilesDir(): File = demoDir(base.filesDir)

    override fun getNoBackupFilesDir(): File = demoDir(base.noBackupFilesDir)

    override fun getCacheDir(): File = demoDir(base.cacheDir)

    override fun getDatabasePath(name: String): File {
        val database = base.getDatabasePath(name)
        val parent = database.parentFile ?: return database
        return File(demoDir(parent), database.name)
    }

    /**
     * SQLite creates the database file but not its directory, and plain file stores do not create
     * their parent either, so the namespace directory has to exist like the real one does.
     */
    private fun demoDir(real: File): File = DemoNamespace.demoPath(real).also { it.mkdirs() }
}
