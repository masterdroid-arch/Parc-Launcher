package com.brightblu.parc.feature.settings.onboarding

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import com.brightblu.parc.core.data.notifications.ParcNotificationListenerService
import com.brightblu.parc.feature.settings.notifications.NotificationAccessLink
import com.brightblu.parc.feature.settings.notifications.NotificationAccessStatus

/**
 * Reads the onboarding state from the platform (T18). No permission is used by any probe; each one
 * returns null when it cannot answer, which the flow renders as "no claim" instead of a guess.
 *
 * - Home role: `RoleManager.isRoleHeld(ROLE_HOME)`. No permission of its own.
 * - Notification access: T15's listener grant, read through [NotificationAccessLink], so the flow
 *   shares one definition of "granted" with the settings row.
 * - Battery: `isIgnoringBatteryOptimizations` for Parc's own package. Unverified on this device build
 *   (see the T18 report); a SecurityException or a missing service yields null.
 * - Autostart: nothing to read. [OnboardingFlow] always reports Unknown for it.
 */
class AndroidOnboardingProbe(private val context: Context) : OnboardingProbe {

    override fun read(): OnboardingProbes = OnboardingProbes(
        homeRoleHeld = homeRoleHeld(),
        notificationAccessGranted = notificationAccessGranted(),
        batteryUnrestricted = batteryUnrestricted(),
        usageAccessGranted = usageAccessGranted(),
    )

    private fun homeRoleHeld(): Boolean? = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrNull()

    private fun notificationAccessGranted(): Boolean? = runCatching {
        val listener = ComponentName(context, ParcNotificationListenerService::class.java)
        NotificationAccessLink.of(context, listener).status == NotificationAccessStatus.Granted
    }.getOrNull()

    private fun usageAccessGranted(): Boolean? = runCatching {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return@runCatching null
        ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    private fun batteryUnrestricted(): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    companion object {
        fun of(context: Context): AndroidOnboardingProbe =
            AndroidOnboardingProbe(context.applicationContext)
    }
}
