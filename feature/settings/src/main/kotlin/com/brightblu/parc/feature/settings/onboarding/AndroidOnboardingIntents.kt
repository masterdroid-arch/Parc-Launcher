package com.brightblu.parc.feature.settings.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Turns an [OnboardingTarget] into the Intent that opens the matching Settings page (T18).
 *
 * [open] resolves the candidate list from [OnboardingTargets] against this device and starts the first
 * page that exists, falling back to the last candidate. It returns false when nothing could be
 * started, so a host can stay silent instead of crashing. No permission is needed and no setting is
 * changed: the user flips the switch.
 *
 * Package visibility note: `resolveActivity` is filtered on API 30+, so a page could report missing
 * while still being startable. The fallback chain covers that, and the T18 report marks the emulator
 * behaviour as the only thing verified.
 */
object AndroidOnboardingIntents {

    /** The Intent for [resolved], with the package data and extras that page needs. */
    fun intentFor(context: Context, resolved: ResolvedAction): Intent {
        val intent = Intent(resolved.action)
        when (resolved.target) {
            OnboardingTarget.AppInfo, OnboardingTarget.UsageAccess ->
                intent.data = Uri.fromParts("package", context.packageName, null)

            // Vendor convention: the Autostart activity is matched through the default category.
            OnboardingTarget.Autostart -> intent.addCategory(Intent.CATEGORY_DEFAULT)

            OnboardingTarget.NotificationAccess ->
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

            OnboardingTarget.HomeRole, OnboardingTarget.BatteryOptimization -> Unit
        }
        return intent
    }

    /** The page Parc would open for [target] on this device, without starting it. */
    fun resolve(context: Context, target: OnboardingTarget): ResolvedAction {
        val action = OnboardingTargets.pick(target) { candidate ->
            context.packageManager.resolveActivity(
                intentFor(context, ResolvedAction(target, candidate)),
                0,
            ) != null
        }
        return ResolvedAction(target, action)
    }

    /** Opens the page for [target]. Returns false when nothing could be started. */
    fun open(context: Context, target: OnboardingTarget): Boolean {
        val intent = intentFor(context, resolve(context, target))
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
