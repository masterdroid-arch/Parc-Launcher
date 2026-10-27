package com.brightblu.parc.core.data.demo

import java.io.File

/**
 * T121: the demo store. It lives wherever the caller's context resolves `filesDir`, which
 * T120a/T120b have already redirected to `files/demo` while demo mode is on. The class does not
 * know or care which namespace it is in, so building it in a real run is a caller bug, not a
 * second data path: the stats hub constructs it only when `DemoMode.enabled`.
 *
 * Blocking file I/O: call [read] off the main thread.
 */
class DemoStatsStore(private val file: File) {

    /**
     * Reads the seeded sample, or writes [DemoStatsFixture.sample] on the first run, after a
     * corrupt file, or when the stored values fail [DemoStatsRules]. A failed write still returns
     * the sample, so the demo hub renders instead of showing an I/O error.
     */
    fun read(): DemoSample {
        val stored = runCatching {
            if (file.isFile) DemoStatsJson.decode(file.readText(Charsets.UTF_8)) else null
        }.getOrNull()
        if (stored != null) return stored
        val seeded = DemoStatsFixture.sample()
        runCatching { write(seeded) }
        return seeded
    }

    fun write(sample: DemoSample) {
        file.parentFile?.mkdirs()
        file.writeText(DemoStatsJson.encode(sample), Charsets.UTF_8)
    }

    companion object {
        /** Relative to `filesDir`; with demo on that is `files/demo/demo_stats.json`. */
        const val FileName = "demo_stats.json"
    }
}
