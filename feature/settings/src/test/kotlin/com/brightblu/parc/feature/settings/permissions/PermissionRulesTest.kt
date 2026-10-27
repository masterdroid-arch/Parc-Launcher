package com.brightblu.parc.feature.settings.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionRulesTest {
    @Test fun focusPolicyAccessIsOptionalAndExplained() {
        val row = PermissionRow(OptionalPermission.NotificationPolicy, PermissionState.NotGranted)
        assertEquals("Do Not Disturb access", row.permission.title)
        org.junit.Assert.assertTrue(row.permission.reason.contains("Off by default"))
        org.junit.Assert.assertNotNull(row.actionLabel)
    }

    @Test fun statesFollowTheProbes() {
        val rows = PermissionRules.rows(true, false, null, true, false, true)
        assertEquals(listOf(PermissionState.Granted, PermissionState.NotGranted, PermissionState.Unknown, PermissionState.Granted, PermissionState.NotGranted, PermissionState.Granted), rows.map { it.state })
        assertEquals(listOf("Allowed", "Not allowed", "Can't tell", "Allowed", "Not allowed", "Allowed"), rows.map { it.stateLabel })
    }

    @Test fun grantedRowsOfferNoAction() {
        val rows = PermissionRules.rows(true, true, true, true, true, true)
        assertEquals(listOf(null, null, null, null, null, null), rows.map { it.actionLabel })
        assertNull(rows.first().actionLabel)
    }

    @Test fun contactsAsksInAppOthersOpenSettings() {
        val rows = PermissionRules.rows(false, false, false, false, false, false)
        assertEquals("Open usage access settings", rows[0].actionLabel)
        assertEquals("Open notification access settings", rows[1].actionLabel)
        assertEquals("Allow contacts", rows[2].actionLabel)
        assertEquals("Allow calendar", rows[3].actionLabel)
        assertEquals("Allow notifications", rows[4].actionLabel)
        assertEquals("Allow files", rows[5].actionLabel)
    }

    @Test fun unknownStillOffersTheAction() {
        assertEquals("Open usage access settings", PermissionRules.rows(null, true, true, true, true, true).first().actionLabel)
    }

    @Test fun summaryCounts() {
        assertEquals("All allowed", PermissionRules.summary(PermissionRules.rows(true, true, true, true, true, true)))
        assertEquals("3 of 6 allowed", PermissionRules.summary(PermissionRules.rows(true, false, null, true, false, true)))
        assertEquals("0 of 6 allowed", PermissionRules.summary(PermissionRules.rows(null, null, null, null, null, null)))
    }

    @Test fun calendarRowIsFourthExplainedAndAsksInApp() {
        val rows = PermissionRules.rows(true, true, true, false, true, true)
        val row = rows[3]
        assertEquals(OptionalPermission.Calendar, row.permission)
        assertEquals("Calendar", row.permission.title)
        org.junit.Assert.assertTrue(row.permission.reason.contains("Agenda"))
        assertEquals(PermissionState.NotGranted, row.state)
        assertEquals("Allow calendar", row.actionLabel)
        assertNull(PermissionRules.rows(true, true, true, true, true, true)[3].actionLabel)
        assertEquals(PermissionState.Unknown, PermissionRules.rows(true, true, true, null, true, true)[3].state)
    }

    @Test fun timerNotificationsRowIsFifthExplainedAndAsksInApp() {
        val row = PermissionRules.rows(true, true, true, true, false, true)[4]
        assertEquals(OptionalPermission.Notifications, row.permission)
        assertEquals("Notifications", row.permission.title)
        org.junit.Assert.assertTrue(row.permission.reason.contains("timer"))
        assertEquals("Not allowed", row.stateLabel)
        assertEquals("Allow notifications", row.actionLabel)
        assertNull(PermissionRules.rows(true, true, true, true, true, true)[4].actionLabel)
        assertEquals(PermissionState.Unknown, PermissionRules.rows(true, true, true, true, null, true)[4].state)
        assertEquals("5 of 6 allowed", PermissionRules.summary(PermissionRules.rows(true, true, true, true, false, true)))
    }

    @Test fun dumpCommandNamesThePackage() {
        assertEquals("adb shell pm grant com.example.app android.permission.DUMP", PermissionRules.dumpGrantCommand("com.example.app"))
    }

    @Test fun filesRowIsLastExplainedAndAsksInApp() {
        val rows = PermissionRules.rows(true, true, true, true, true, false)
        val row = rows.last()
        assertEquals(OptionalPermission.Files, row.permission)
        org.junit.Assert.assertTrue(row.permission.reason.contains("file name"))
        assertEquals("Not allowed", row.stateLabel)
        assertEquals("Allow files", row.actionLabel)
        assertNull(PermissionRules.rows(true, true, true, true, true, true).last().actionLabel)
        assertEquals(PermissionState.Unknown, PermissionRules.rows(true, true, true, true, true, null).last().state)
        assertEquals("5 of 6 allowed", PermissionRules.summary(rows))
    }

    @Test fun calendarReasonMentionsSearch() {
        org.junit.Assert.assertTrue(OptionalPermission.Calendar.reason.contains("search"))
    }

    @Test fun anyOneMediaPermissionCountsAsAllowed() {
        assertEquals(true, FilesPermissionRules.state(listOf(false, true, false)))
        assertEquals(false, FilesPermissionRules.state(listOf(false, false, null)))
        assertNull(FilesPermissionRules.state(listOf(null, null, null)))
        assertEquals(3, FilesPermissionRules.permissions.size)
    }
}
