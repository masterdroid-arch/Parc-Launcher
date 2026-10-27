package com.brightblu.parc.core.data.stats

import java.io.File

/** Minimal file-reading boundary for the `/sys` cpufreq files, so the probe stays testable. */
internal interface SysfsReader {
    /** File contents, trimmed, or null when the file is missing or unreadable. */
    fun read(path: String): String?
}

/** Production reader; every failure (missing file, SELinux denial) becomes null. */
internal object FileSysfsReader : SysfsReader {
    override fun read(path: String): String? = try {
        val file = File(path)
        if (file.canRead()) file.readText().trim() else null
    } catch (unexpected: Throwable) {
        null
    }
}
