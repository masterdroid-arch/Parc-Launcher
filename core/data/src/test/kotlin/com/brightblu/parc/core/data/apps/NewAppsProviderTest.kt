package com.brightblu.parc.core.data.apps

import com.brightblu.parc.model.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T47 new apps: the 7-day cutoff, ordering, the PackageManager join and the provider's fake clock. */
class NewAppsProviderTest {

    private class FakeInstallTimes(var times: Map<String, Long>?) : PackageInstallTimes {
        override fun firstInstallTimes(): Map<String, Long>? = times
    }

    private val ownPackage = "com.brightblu.parc"
    private val now = epoch(2026, 10, 17, 9, 0, nairobi)

    private fun recent(
        apps: List<AppEntry>,
        times: Map<String, Long> = emptyMap(),
        includeSystem: Boolean = true,
        limit: Int = NewAppsProvider.DefaultLimit,
        at: Long = now,
    ): List<NewApp> = NewApps.recent(
        apps = apps,
        installTimes = times,
        nowMillis = at,
        ownPackage = ownPackage,
        includeSystem = includeSystem,
        limit = limit,
    )

    private fun provider(
        times: PackageInstallTimes,
        apps: List<AppEntry>,
        at: () -> Long = { now },
    ): AndroidNewAppsProvider = AndroidNewAppsProvider(
        installTimes = times,
        installed = { apps },
        ownPackage = ownPackage,
        clock = at,
        ioDispatcher = Dispatchers.Unconfined,
        includeSystem = true,
    )

    @Test
    fun `the cutoff includes exactly seven days and excludes one millisecond more`() {
        val cutoff = now - NewApps.WindowMillis
        val onCutoff = testEntry("cutoff", installTime = cutoff)
        val justOlder = testEntry("older", installTime = cutoff - 1L)

        val result = recent(listOf(onCutoff, justOlder))

        assertEquals(listOf(onCutoff.id), result.map { it.app.id })
    }

    @Test
    fun `newest first, then label and id for a tie`() {
        val old = testEntry("old", label = "Zulu", installTime = now - 2L * DayMillis)
        val new = testEntry("new", label = "Alpha", installTime = now - DayMillis)
        val tieB = testEntry("tieB", label = "Bravo", installTime = now)
        val tieA = testEntry("tieA", label = "Alpha", installTime = now)

        val result = recent(listOf(old, new, tieB, tieA))

        assertEquals(
            listOf("Alpha", "Bravo", "Alpha", "Zulu"),
            result.map { it.app.label },
        )
        assertEquals(listOf(tieA.id, tieB.id, new.id, old.id), result.map { it.app.id })
    }

    @Test
    fun `system apps are included by default and can be excluded`() {
        val system = testEntry("system", isSystem = true, installTime = now)
        val user = testEntry("user", installTime = now)

        assertEquals(
            listOf(system.id, user.id),
            recent(listOf(system, user)).map { it.app.id },
        )
        assertEquals(
            listOf(user.id),
            recent(listOf(system, user), includeSystem = false).map { it.app.id },
        )
    }

    @Test
    fun `suspended apps are excluded because they cannot be launched`() {
        val suspended = testEntry("suspended", installTime = now, suspended = true)
        val active = testEntry("active", installTime = now)

        assertEquals(listOf(active.id), recent(listOf(suspended, active)).map { it.app.id })
    }

    @Test
    fun `Parc itself is never a new app`() {
        val parc = testEntry("parc", packageName = ownPackage, installTime = now)
        val app = testEntry("app", installTime = now)

        assertEquals(listOf(app.id), recent(listOf(parc, app)).map { it.app.id })
    }

    @Test
    fun `the PackageManager install time wins over the entry field`() {
        val app = testEntry("app", installTime = now - 30L * DayMillis)

        val result = recent(listOf(app), times = mapOf(app.packageName to now - DayMillis))

        assertEquals(now - DayMillis, result.single().installedAtMillis)
    }

    @Test
    fun `the entry install time is the fallback when PackageManager hides the package`() {
        val app = testEntry("app", installTime = now - DayMillis)

        val result = recent(listOf(app), times = emptyMap())

        assertEquals(now - DayMillis, result.single().installedAtMillis)
    }

    @Test
    fun `a zero PackageManager time falls back to the entry install time`() {
        val app = testEntry("app", installTime = now - DayMillis)

        val result = recent(listOf(app), times = mapOf(app.packageName to 0L))

        assertEquals(now - DayMillis, result.single().installedAtMillis)
    }

    @Test
    fun `a zero install time is never new`() {
        val app = testEntry("app", installTime = 0L)

        assertTrue(recent(listOf(app)).isEmpty())
    }

    @Test
    fun `a future install time counts as newest rather than being discarded`() {
        val app = testEntry("app", installTime = now + DayMillis)

        assertEquals(now + DayMillis, recent(listOf(app)).single().installedAtMillis)
    }

    @Test
    fun `the limit is applied after ordering and zero means empty`() {
        val apps = (1..4).map { index ->
            testEntry("app$index", installTime = now - index * HourMillis)
        }

        assertEquals(
            listOf(apps[0].id, apps[1].id),
            recent(apps, limit = 2).map { it.app.id },
        )
        assertTrue(recent(apps, limit = 0).isEmpty())
    }

    @Test
    fun `the provider reads a fixed clock and maps install times`() = runTest {
        val app = testEntry("app", installTime = 0L)
        val times = FakeInstallTimes(mapOf(app.packageName to now - DayMillis))

        val result = provider(times, listOf(app)).recent()

        assertEquals(listOf(app.id), result.map { it.app.id })
        assertEquals(now - DayMillis, result.single().installedAtMillis)
    }

    @Test
    fun `the provider falls back to entry times when the PackageManager read fails`() = runTest {
        val app = testEntry("app", installTime = now - DayMillis)

        val result = provider(FakeInstallTimes(null), listOf(app)).recent()

        assertEquals(listOf(app.id), result.map { it.app.id })
    }
}
