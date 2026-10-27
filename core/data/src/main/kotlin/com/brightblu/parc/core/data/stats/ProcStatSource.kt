package com.brightblu.parc.core.data.stats

import java.io.File

/**
 * The `/proc/stat` boundary, so the CPU-load parser can be tested without a device. Kept separate
 * from [SysfsReader] because `/proc` and `/sys` are different mounts with different SELinux rules
 * on Android (T58).
 */
internal interface ProcStatSource {
    /** The file contents, or null when the kernel or SELinux denies the read. */
    fun read(): String?
}

/** Production reader. Any failure becomes null; it never throws and needs no permission. */
internal object FileProcStatSource : ProcStatSource {
    private const val Path = "/proc/stat"

    override fun read(): String? = try {
        val file = File(Path)
        if (file.canRead()) file.readText() else null
    } catch (unexpected: Throwable) {
        null
    }
}
