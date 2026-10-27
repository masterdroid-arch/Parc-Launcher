package com.brightblu.parc.core.data

import android.content.ActivityNotFoundException
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.model.AppId

/**
 * Launches an app and turns every expected failure into a [LaunchOutcome]. Messages are
 * deliberately generic: no package or class names reach the log in release builds.
 */
internal class AppLauncher(
    private val source: LauncherSource,
    private val logWarn: (String) -> Unit = {},
) {
    fun launch(id: AppId): LaunchOutcome {
        val outcome = try {
            source.startMainActivity(id.flattenedComponent(), id.userSerial())
        } catch (e: ActivityNotFoundException) {
            LaunchOutcome.Unavailable
        } catch (e: SecurityException) {
            LaunchOutcome.Unavailable
        } catch (e: IllegalStateException) {
            LaunchOutcome.Unavailable
        }
        when (outcome) {
            LaunchOutcome.Started -> Unit
            LaunchOutcome.ProfilePaused -> logWarn("launch skipped: profile paused")
            LaunchOutcome.ProfileRemoved -> logWarn("launch skipped: profile removed")
            LaunchOutcome.Unavailable -> logWarn("launch failed: unavailable")
        }
        return outcome
    }
}
