package com.brightblu.parc

import android.util.AtomicFile
import com.brightblu.parc.model.StartupSnapshot
import com.brightblu.parc.model.StartupSnapshotJson
import java.io.File
import java.io.IOException

/** IO-dispatcher only. Atomic replacement keeps a killed writer from destroying the last snapshot. */
internal class StartupCache(file: File) {
    private val atomic = AtomicFile(file)

    fun read(): StartupSnapshot? = try {
        StartupSnapshotJson.decode(atomic.openRead().bufferedReader().use { it.readText() })
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun write(snapshot: StartupSnapshot) {
        val bytes = StartupSnapshotJson.encode(snapshot).toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (_: IOException) {
            atomic.failWrite(stream)
        }
    }
}
