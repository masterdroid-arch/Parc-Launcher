package com.brightblu.parc.core.data.profiles

import com.brightblu.parc.core.data.SourceProfileSet
import com.brightblu.parc.core.data.sourceProfile
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {

    private val personal = sourceProfile(0, ProfileKind.Personal)
    private val work = sourceProfile(10, ProfileKind.Work)
    private val otherUser = sourceProfile(11, ProfileKind.Other)

    @Test
    fun `the calling user is personal and an accessible profile is work`() {
        assertEquals(ProfileKind.Personal, classifyProfile(0, 0, emptySet()))
        assertEquals(ProfileKind.Work, classifyProfile(10, 0, setOf(10)))
        assertEquals(ProfileKind.Work, classifyProfile(10, 0, setOf(10, 11)))
    }

    @Test
    fun `a serial outside the accessible profiles is another user`() {
        assertEquals(ProfileKind.Other, classifyProfile(11, 0, setOf(10)))
        assertEquals(ProfileKind.Other, classifyProfile(11, 0, emptySet()))
    }

    @Test
    fun `the current serial wins over a stale accessible list`() {
        assertEquals(ProfileKind.Personal, classifyProfile(0, 0, setOf(0, 10)))
    }

    @Test
    fun `a source snapshot maps in order and keeps the paused flag`() {
        val set = SourceProfileSet(0, listOf(personal, work.copy(paused = true))).toProfileSet()

        assertEquals(0L, set.currentSerial)
        assertEquals(listOf(0L, 10L), set.profiles.map { it.serial })
        assertEquals(listOf(ProfileKind.Personal, ProfileKind.Work), set.profiles.map { it.kind })
        assertEquals(listOf(false, true), set.profiles.map { it.paused })
        assertTrue(set.loaded)
        assertTrue(set.hasWorkProfile)
        assertEquals(setOf(10L), set.workSerials)
    }

    @Test
    fun `a duplicate serial keeps the first occurrence`() {
        val set = SourceProfileSet(
            0,
            listOf(personal, work, work.copy(paused = true), otherUser),
        ).toProfileSet()

        assertEquals(listOf(0L, 10L, 11L), set.profiles.map { it.serial })
        assertFalse(set.profiles.first { it.serial == 10L }.paused)
    }

    @Test
    fun `an empty source set falls back to the calling user`() {
        assertEquals(SourceProfileSet(0, listOf(personal)), personalOnlyProfileSet(0))
        val set = personalOnlyProfileSet(7).toProfileSet()
        assertEquals(7L, set.currentSerial)
        assertEquals(ProfileKind.Personal, set.kindOf(7))
        assertFalse(set.hasWorkProfile)
    }

    @Test
    fun `an unknown set marks nothing and blocks nothing`() {
        val unknown = ProfileSet()
        assertFalse(unknown.loaded)
        assertNull(unknown.kindOf(10))
        assertFalse(unknown.isWork(10))
        assertFalse(unknown.isPaused(10))
        assertFalse(unknown.isProfileApp(AppId("p/p.Main@10")))
        assertFalse(unknown.isRemoved(AppId("p/p.Main@10")))
    }

    @Test
    fun `work and personal are told apart by the serial in the app id`() {
        val personalId = AppId("p/p.Main@0")
        val workId = AppId("p/p.Main@10")
        val otherId = AppId("p/p.Main@11")
        val set = ProfileSet(0, listOf(personal, work, otherUser).map {
            ProfileInfo(it.serial, it.kind, it.paused)
        })

        assertEquals(ProfileKind.Personal, set.kindOf(personalId))
        assertEquals(ProfileKind.Work, set.kindOf(workId))
        assertEquals(ProfileKind.Other, set.kindOf(otherId))
        assertTrue(set.isWork(workId))
        assertFalse(set.isWork(personalId))
        assertFalse(set.isWork(otherId))
        assertTrue(set.isProfileApp(workId))
        assertFalse(set.isProfileApp(personalId))
    }

    @Test
    fun `a paused profile is reported per app`() {
        val paused = ProfileSet(0, listOf(ProfileInfo(0, ProfileKind.Personal, false), ProfileInfo(10, ProfileKind.Work, true)))

        assertTrue(paused.isPaused(AppId("p/p.Main@10")))
        assertFalse(paused.isPaused(AppId("p/p.Main@0")))
        assertFalse(paused.isPaused(AppId("p/p.Main@11")))
    }

    @Test
    fun `a removed profile is absent but the personal user is never removed`() {
        val afterRemoval = ProfileSet(0, listOf(ProfileInfo(0, ProfileKind.Personal, false)))

        assertTrue(afterRemoval.isRemoved(AppId("p/p.Main@10")))
        assertFalse(afterRemoval.isRemoved(AppId("p/p.Main@0")))
        assertFalse(afterRemoval.isWork(AppId("p/p.Main@10")))
    }

    @Test
    fun `the app entry extensions follow the profile set`() {
        val source = ProfileSet(0, listOf(ProfileInfo(0, ProfileKind.Personal, false), ProfileInfo(10, ProfileKind.Work, true)))
        val personalEntry = AppEntry(AppId("p/p.Main@0"), "Main", "p", false, 0L)
        val workEntry = AppEntry(AppId("p/p.Main@10"), "Main", "p", false, 0L)

        assertFalse(personalEntry.isWork(source))
        assertTrue(workEntry.isWork(source))
        assertTrue(workEntry.isProfilePaused(source))
        assertFalse(personalEntry.isProfilePaused(source))
    }
}
