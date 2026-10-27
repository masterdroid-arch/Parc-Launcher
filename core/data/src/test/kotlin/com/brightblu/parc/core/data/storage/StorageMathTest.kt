package com.brightblu.parc.core.data.storage

import com.brightblu.parc.core.data.apps.testEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure T52 storage maths: the launchable join, clamping and the deterministic order. */
class StorageMathTest {

    @Test
    fun `unknown packages are dropped`() {
        val entries = StorageMath.entries(
            samples = listOf(storageSample("com.test.known", 10), storageSample("com.test.gone", 20)),
            appsByPackage = mapOf("com.test.known" to testEntry("known")),
        )

        assertEquals(listOf("com.test.known"), entries.map { it.packageName })
    }

    @Test
    fun `sorting is total, then label, package and id`() {
        val first = testEntry("b", packageName = "com.test.b", label = "Beta")
        val second = testEntry("a", packageName = "com.test.a", label = "Beta")
        val third = testEntry("c", packageName = "com.test.c", label = "Alpha")
        val entries = StorageMath.entries(
            samples = listOf(
                storageSample("com.test.a", 10),
                storageSample("com.test.c", 50),
                storageSample("com.test.b", 10),
            ),
            appsByPackage = listOf(first, second, third).associateBy { it.packageName },
        )

        assertEquals(listOf("com.test.c", "com.test.a", "com.test.b"), entries.map { it.packageName })
    }

    @Test
    fun `all three counters are clamped independently`() {
        val entry = StorageMath.entries(
            samples = listOf(storageSample("com.test.a", -1, -2, -3)),
            appsByPackage = mapOf("com.test.a" to testEntry("a")),
        ).single()

        assertEquals(0L, entry.appBytes)
        assertEquals(0L, entry.dataBytes)
        assertEquals(0L, entry.cacheBytes)
        assertEquals(0L, entry.totalBytes)
    }

    @Test
    fun `no samples means no entries`() {
        assertTrue(StorageMath.entries(emptyList(), emptyMap()).isEmpty())
    }
}
