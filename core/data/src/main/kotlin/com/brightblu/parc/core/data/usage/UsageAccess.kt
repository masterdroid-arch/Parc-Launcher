package com.brightblu.parc.core.data.usage

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * The usage-access special permission (PROJECT.md §6.3, decision 15). It is an app op, not a runtime
 * permission: Parc declares no manifest permission for it and dev builds receive it with
 * `adb shell appops set com.brightblu.parc GET_USAGE_STATS allow` (or the Usage access settings page).
 *
 * One read for every consumer (T13 enrichment, T47 screen time and last-app), so an unverified
 * platform answer can only hide optional data and never crash a caller.
 *
 * `unsafeCheckOpNoThrow` is the appop read available since API 29 and therefore works on the API 34
 * emulator as well as Android 16. API 36 deprecates it in favour of a flagged newer overload, so the
 * deprecation is suppressed deliberately; both read the same app op and neither throws.
 */
internal object UsageAccess {

    /** True only while GET_USAGE_STATS is allowed for this package. Never throws. */
    fun granted(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps == null) {
            false
        } else {
            @Suppress("DEPRECATION")
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }
    } catch (t: Throwable) {
        false
    }
}
