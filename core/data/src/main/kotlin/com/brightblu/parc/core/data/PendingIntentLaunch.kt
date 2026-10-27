package com.brightblu.parc.core.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle

/**
 * OPUS6 (audit F9): sends another app's PendingIntent (a notification's or a media session's
 * "open" action) in answer to a tap in Parc.
 *
 * From Android 14, an app that sends a PendingIntent must opt in to lend its foreground state, or
 * the other app's activity start is blocked as a background start: the tap did nothing while Parc's
 * menu closed as if it had worked. Parc is the visible app when the user taps, so it opts in for that
 * one send ("allow if visible" on Android 16, "allowed" before).
 */
object PendingIntentLaunch {
    fun options(sdk: Int = Build.VERSION.SDK_INT): Bundle {
        // Android 13 (API 33) has no background-start opt-in and does not need one; the plain
        // options bundle is correct there. Parc supports Android 13 and newer.
        if (sdk < 34) return Bundle.EMPTY
        return optInOptions(sdk)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun optInOptions(sdk: Int): Bundle {
        val mode = if (sdk >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }

    /** Sends [intent] as a user tap. Throws [PendingIntent.CanceledException] like [PendingIntent.send]. */
    fun send(intent: PendingIntent) {
        intent.send(null, 0, null, null, null, null, options())
    }
}
