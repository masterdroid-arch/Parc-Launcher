package com.brightblu.parc.feature.settings.onboarding

import android.provider.Settings
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intent candidate lists: no permission-triggering action, an ordered preference per target, and a
 * defined fallback when nothing resolves.
 */
class OnboardingTargetsTest {

    @Test
    fun `every candidate is allowed and none is forbidden`() {
        OnboardingTarget.entries.forEach { target ->
            OnboardingTargets.candidatesFor(target).forEach { action ->
                assertTrue("$action is not on the allowed list", action in OnboardingTargets.Allowed)
                assertTrue("$action must never be offered", action !in OnboardingTargets.Forbidden)
            }
        }
    }

    @Test
    fun `the request-ignore-battery action is never a candidate`() {
        val requestPermission = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        OnboardingTarget.entries.forEach { target ->
            assertTrue(requestPermission !in OnboardingTargets.candidatesFor(target))
        }
    }

    @Test
    fun `each target prefers its own page`() {
        assertEquals(
            listOf(OnboardingTargets.AppDetailsSettings),
            OnboardingTargets.candidatesFor(OnboardingTarget.AppInfo),
        )
        assertEquals(
            OnboardingTargets.HomeSettings,
            OnboardingTargets.candidatesFor(OnboardingTarget.HomeRole).first(),
        )
        assertEquals(
            OnboardingTargets.IgnoreBatteryOptimizationSettings,
            OnboardingTargets.candidatesFor(OnboardingTarget.BatteryOptimization).first(),
        )
        assertEquals(
            OnboardingTargets.VendorAutoStart,
            OnboardingTargets.candidatesFor(OnboardingTarget.Autostart).first(),
        )
        assertEquals(
            OnboardingTargets.NotificationListenerSettings,
            OnboardingTargets.candidatesFor(OnboardingTarget.NotificationAccess).first(),
        )
    }

    @Test
    fun `every target ends on a page that exists on a plain Android build`() {
        OnboardingTarget.entries.forEach { target ->
            val last = OnboardingTargets.candidatesFor(target).last()
            assertTrue(
                "$target falls back to $last",
                last == OnboardingTargets.AppDetailsSettings || last == OnboardingTargets.Settings,
            )
        }
    }

    @Test
    fun `pick returns the first candidate the device accepts`() {
        val picked = OnboardingTargets.pick(OnboardingTarget.BatteryOptimization) { it.contains("BATTERY") }

        assertEquals(OnboardingTargets.IgnoreBatteryOptimizationSettings, picked)
    }

    @Test
    fun `pick falls back to the last candidate when nothing resolves`() {
        val picked = OnboardingTargets.pick(OnboardingTarget.Autostart) { false }

        assertEquals(OnboardingTargets.AppDetailsSettings, picked)
    }

    @Test
    fun `the documented actions match the platform Settings constants`() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, OnboardingTargets.AppDetailsSettings)
        assertEquals(Settings.ACTION_HOME_SETTINGS, OnboardingTargets.HomeSettings)
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            OnboardingTargets.IgnoreBatteryOptimizationSettings,
        )
        assertEquals(Settings.ACTION_SETTINGS, OnboardingTargets.Settings)
    }

    @Test
    fun `the notification access action matches the one the settings row opens`() {
        assertEquals(NotificationAccessLink.Action, OnboardingTargets.NotificationListenerSettings)
    }
}
