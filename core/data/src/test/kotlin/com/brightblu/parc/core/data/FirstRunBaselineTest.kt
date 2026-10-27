package com.brightblu.parc.core.data

import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The first-seed baseline (T62): baseline apps stay unfiled, later installs enter New. */
class FirstRunBaselineTest {

    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")
    private val gamma = appId("com.gamma/com.gamma.Main")

    private fun seeded(): ParcLayout = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            if (spec.id == "A1") ArcAssignment(spec, mapOf(0 to Slot.App(alpha))) else ArcAssignment(spec, emptyMap())
        },
        pins = setOf(alpha),
    )

    @Test
    fun `a fresh seed records the moment and only later installs enter New`() = runTest {
        val store = FakeFirstRunStore()
        val h = RepositoryHarness(seed = seeded(), firstRunStore = store, now = 5_000L)

        h.repository.load(listOf(appEntry(alpha, installTime = 100L), appEntry(beta, installTime = 6_000L)))

        assertEquals(5_000L, store.value)
        h.repository.syncInstalled(
            listOf(
                appEntry(alpha, installTime = 100L),
                appEntry(beta, installTime = 6_000L),
                appEntry(gamma, installTime = 200L),
            ),
        )

        val newFolder = h.repository.layout.value.folders.first { it.id == NewFolderId }
        assertEquals(listOf(beta), newFolder.appIds)
    }

    @Test
    fun `an existing baseline is never moved by a later start`() = runTest {
        val store = FakeFirstRunStore(value = 1_000L)
        val h = RepositoryHarness(seed = seeded(), firstRunStore = store, now = 9_000L)

        h.repository.load(listOf(appEntry(alpha)))

        assertEquals(1_000L, store.value)
    }

    @Test
    fun `a layout that predates the marker keeps the old New behaviour`() = runTest {
        val store = FakeFirstRunStore(value = null)
        val h = RepositoryHarness(seed = seeded(), firstRunStore = store)

        h.repository.load(listOf(appEntry(alpha, installTime = 100L)))
        store.value = null
        h.repository.syncInstalled(listOf(appEntry(alpha, installTime = 100L), appEntry(beta, installTime = 100L)))

        assertNull(store.value)
        assertEquals(
            listOf(beta),
            h.repository.layout.value.folders.first { it.id == NewFolderId }.appIds,
        )
    }
}
