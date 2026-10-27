package com.brightblu.parc.core.data.storage

import com.brightblu.parc.core.data.apps.testEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T52 app storage: grant handling, the package join, per-package isolation and the top-5 list. */
class AppStorageRepositoryTest {

    @Test
    fun `a missing usage grant is Unavailable and no package is queried`() = runTest {
        val probe = FakeStorageProbe().apply { granted = false }

        val result = storageRepository(probe) { listOf(testEntry("a")) }.read()

        assertEquals(StorageResult.Unavailable, result)
        assertTrue(probe.requested.isEmpty())
    }

    @Test
    fun `a grant read failure is Unavailable rather than a crash`() = runTest {
        val probe = FakeStorageProbe().apply { throwOnGrant = true }

        assertEquals(StorageResult.Unavailable, storageRepository(probe) { emptyList() }.read())
    }

    @Test
    fun `storage sums app data and cache for each launchable app`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.a"] = storageSample("com.test.a", 10, 20, 30)
            samples["com.test.b"] = storageSample("com.test.b", 5, 0, 1)
        }

        val result = storageRepository(probe) {
            listOf(testEntry("a", packageName = "com.test.a"), testEntry("b", packageName = "com.test.b"))
        }.read() as StorageResult.Available

        assertEquals(2, result.entries.size)
        assertEquals("A", result.entries.first().label)
        assertEquals(60L, result.entries.first().totalBytes)
        assertEquals(6L, result.entries.last().totalBytes)
    }

    @Test
    fun `biggest sorts by total and caps at five`() = runTest {
        val probe = FakeStorageProbe()
        val apps = (1..6).map { index ->
            val packageName = "com.test.app$index"
            probe.samples[packageName] = storageSample(packageName, index * 100L)
            testEntry("app$index", packageName = packageName)
        }

        val result = storageRepository(probe) { apps }.read() as StorageResult.Available

        assertEquals(
            listOf(600L, 500L, 400L, 300L, 200L),
            result.biggest().map { it.totalBytes },
        )
        assertEquals(6, result.entries.size)
    }

    @Test
    fun `equal totals tie-break by label then package`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.z"] = storageSample("com.test.z", 100)
            samples["com.test.a"] = storageSample("com.test.a", 100)
            samples["com.test.m"] = storageSample("com.test.m", 100)
        }

        val result = storageRepository(probe) {
            listOf(
                testEntry("z", packageName = "com.test.z", label = "Zulu"),
                testEntry("a", packageName = "com.test.a", label = "Alpha"),
                testEntry("m", packageName = "com.test.m", label = "Mike"),
            )
        }.read() as StorageResult.Available

        assertEquals(listOf("Alpha", "Mike", "Zulu"), result.entries.map { it.label })
    }

    @Test
    fun `zero-byte apps are listed but never pad the biggest list`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.zero"] = storageSample("com.test.zero", 0)
            samples["com.test.real"] = storageSample("com.test.real", 10)
        }

        val result = storageRepository(probe) {
            listOf(testEntry("zero"), testEntry("real"))
        }.read() as StorageResult.Available

        assertEquals(2, result.entries.size)
        assertEquals(listOf("Real"), result.biggest().map { it.label })
    }

    @Test
    fun `one missing package is skipped while the others are returned`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.a"] = storageSample("com.test.a", 10)
        }

        val result = storageRepository(probe) {
            listOf(testEntry("a"), testEntry("b"))
        }.read() as StorageResult.Available

        assertEquals(listOf("A"), result.entries.map { it.label })
        assertEquals(listOf("com.test.a", "com.test.b"), probe.requested)
    }

    @Test
    fun `every package failing after the grant is Unavailable`() = runTest {
        val probe = FakeStorageProbe().apply { granted = true }

        val result = storageRepository(probe) { listOf(testEntry("a"), testEntry("b")) }.read()

        assertEquals(StorageResult.Unavailable, result)
        assertEquals(listOf("com.test.a", "com.test.b"), probe.requested)
    }

    @Test
    fun `an empty app index is an empty Available answer, not a refused one`() = runTest {
        val probe = FakeStorageProbe()

        val result = storageRepository(probe) { emptyList() }.read() as StorageResult.Available

        assertTrue(result.entries.isEmpty())
        assertTrue(probe.requested.isEmpty())
    }

    @Test
    fun `an app index failure yields an empty Available answer`() = runTest {
        val probe = FakeStorageProbe()

        val result = storageRepository(probe) { error("index not ready") }.read()

        assertEquals(StorageResult.Available(emptyList()), result)
    }

    @Test
    fun `negative counters clamp to zero instead of crediting storage`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.a"] = storageSample("com.test.a", -10, 20, -5)
        }

        val result = storageRepository(probe) { listOf(testEntry("a")) }.read()
            as StorageResult.Available

        assertEquals(0L, result.entries.single().appBytes)
        assertEquals(20L, result.entries.single().dataBytes)
        assertEquals(0L, result.entries.single().cacheBytes)
    }

    @Test
    fun `a package with two activities appears once under its smallest id`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.test.a"] = storageSample("com.test.a", 10)
        }

        val result = storageRepository(probe) {
            listOf(
                testEntry("a", packageName = "com.test.a", activity = "Second"),
                testEntry("a", packageName = "com.test.a", activity = "First"),
            )
        }.read() as StorageResult.Available

        assertEquals(1, result.entries.size)
        assertEquals("com.test.a/com.test.a.First@0", result.entries.single().appId.value)
    }

    @Test
    fun `the launcher's own package is a real app size and is included`() = runTest {
        val probe = FakeStorageProbe().apply {
            samples["com.brightblu.parc"] = storageSample("com.brightblu.parc", 42)
        }

        val result = storageRepository(probe) {
            listOf(testEntry("parc", packageName = "com.brightblu.parc", label = "Parc"))
        }.read() as StorageResult.Available

        assertEquals("Parc", result.entries.single().label)
    }
}
