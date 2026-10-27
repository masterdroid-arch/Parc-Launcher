package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The optional-enrichment planner and the package/id mapping it depends on. */
class UsageEnrichmentTest {

    private val instagram = testId("instagram")
    private val threads = testId("threads")

    private val resolve: (String) -> AppId? = { pkg ->
        listOf(instagram, threads).firstOrNull { PackageIds.packageOf(it) == pkg }
    }

    @Test
    fun `packageOf strips the user serial and the activity`() {
        assertEquals("com.test.instagram", PackageIds.packageOf(instagram))
        assertEquals("com.test.it", PackageIds.packageOf(AppId("com.test.it")))
    }

    @Test
    fun `firstByPackage picks the smallest id of a package so attribution is stable`() {
        val other = AppId("com.test.instagram/com.test.instagram.Other@0")
        val byPackage = PackageIds.firstByPackage(listOf(other, instagram, threads))

        assertEquals(instagram, byPackage.getValue("com.test.instagram"))
        assertEquals(threads, byPackage.getValue("com.test.threads"))
    }

    @Test
    fun `events for a package no installed app owns are skipped`() {
        val events = listOf(
            UsageEvent("com.test.instagram", 10L),
            UsageEvent("com.test.gone", 20L),
        )

        val plan = EnrichedLaunches.plan(events, emptyList(), resolve)

        assertEquals(listOf(LaunchRow(instagram, 10L)), plan)
    }

    @Test
    fun `a launch our own log already has is not counted twice`() {
        val events = (1..3).map { UsageEvent("com.test.instagram", it * 10L) }
        val ours = listOf(LaunchRow(instagram, 30L))

        val plan = EnrichedLaunches.plan(events, ours, resolve)

        // Three events minus the one we recorded: the two newest surplus events.
        assertEquals(listOf(LaunchRow(instagram, 10L), LaunchRow(instagram, 20L)), plan)
    }

    @Test
    fun `a log that already knows every event plans nothing`() {
        val events = (1..3).map { UsageEvent("com.test.instagram", it * 10L) }
        val ours = (1..5).map { LaunchRow(instagram, it * 10L) }

        assertTrue(EnrichedLaunches.plan(events, ours, resolve).isEmpty())
    }

    @Test
    fun `the plan is capped at the newest events and returned oldest first`() {
        val events = (1..10).map { UsageEvent("com.test.threads", it * 10L) }

        val plan = EnrichedLaunches.plan(events, emptyList(), resolve, maxPerRun = 3)

        assertEquals(listOf(80L, 90L, 100L), plan.map { it.ts })
        assertEquals(listOf(threads, threads, threads), plan.map { it.appId })
    }

    @Test
    fun `nothing is planned without events or without a resolver result`() {
        assertTrue(EnrichedLaunches.plan(emptyList(), emptyList(), resolve).isEmpty())
        assertTrue(
            EnrichedLaunches.plan(
                listOf(UsageEvent("com.test.instagram", 10L)),
                emptyList(),
                { null },
            ).isEmpty(),
        )
    }
}
